package com.systemhalted.encri.files.ui

import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.client.Right
import com.systemhalted.encri.files.R
import com.systemhalted.encri.files.browse.Listing
import com.systemhalted.encri.files.link.Asked
import com.systemhalted.encri.files.link.LinkState
import com.systemhalted.encri.files.link.Opening

/** The one thing the user can do about the state the screen is in, if there is one. */
enum class Remedy {
    /** Start encri's consent screen, which also offers to turn access for other apps on (encri D120). */
    ASK,

    /** Start encri's unlock screen. */
    UNLOCK,

    /** Ask encri for a session again. */
    RETRY,
}

/**
 * What the screen shows, decided from where the app stands with encri and what the folder holds
 * (AGENTS.md F2). A pure function of its inputs, so every state has a test that needs no phone.
 */
data class Screen(
    /** A string resource: a sentence above the list, or null when the list says it all. */
    val message: Int?,
    /** Arguments to [message]'s format, if it has any. */
    val messageArgs: List<Any> = emptyList(),
    val remedy: Remedy? = null,
    val documents: List<EncriDocument> = emptyList(),
    /** The folder being looked at, or null to show the app's name. Never a name while the vault is locked. */
    val title: String? = null,
    val canGoUp: Boolean = false,
    val canLock: Boolean = false,
    val canReload: Boolean = false,
    /** What the session allows, and so which actions are offered. Empty with no session or a locked vault. */
    val rights: Set<Right> = emptySet(),
    /** Whether the session lacks a right this app asks for, so the menu offers to ask encri again (F5). */
    val wantsMoreRights: Boolean = false,
    /** The name of a document being moved, while the user goes to the folder it goes into (F5). */
    val moving: String? = null,
    /** Whether the folder being looked at can take the document being moved. */
    val canMoveHere: Boolean = false,
) {
    companion object {
        fun of(
            state: LinkState,
            path: List<EncriDocument>,
            listing: Listing,
            moving: EncriDocument? = null,
        ): Screen =
            when (state) {
                LinkState.Idle, LinkState.Connecting -> {
                    Screen(R.string.connecting)
                }

                is LinkState.Refused -> {
                    refused(state.why)
                }

                is LinkState.Open -> {
                    if (state.locked) {
                        Screen(R.string.locked, remedy = Remedy.UNLOCK)
                    } else {
                        open(path, listing).copy(
                            rights = state.session.rights,
                            wantsMoreRights = !state.session.rights.containsAll(Asked.RIGHTS),
                            moving = moving?.name,
                            canMoveHere = moving != null && canTake(path, moving),
                        )
                    }
                }
            }

        private fun refused(why: Opening): Screen =
            when (why) {
                // Never reached: a session is LinkState.Open. Said rather than thrown, because a screen that
                // crashes over a state it was never meant to see helps nobody.
                is Opening.Open -> Screen(R.string.connecting)

                Opening.Locked -> Screen(R.string.locked, remedy = Remedy.UNLOCK)

                Opening.NotAuthorized -> Screen(R.string.not_authorized, remedy = Remedy.ASK)

                Opening.AccessOff -> Screen(R.string.access_off, remedy = Remedy.ASK)

                Opening.NotInstalled -> Screen(R.string.not_installed)

                is Opening.NotTrusted -> Screen(R.string.not_trusted, listOf(why.certificateSha256))

                is Opening.TooOld -> Screen(R.string.too_old, listOf(why.serviceVersion))

                Opening.Unavailable -> Screen(R.string.unavailable, remedy = Remedy.RETRY)
            }

        private fun open(
            path: List<EncriDocument>,
            listing: Listing,
        ): Screen {
            val place =
                Screen(
                    message = null,
                    title = path.takeIf { it.size > 1 }?.last()?.name,
                    canGoUp = path.size > 1,
                    canLock = true,
                    canReload = true,
                )
            return when (listing) {
                Listing.Waiting, Listing.Loading -> {
                    place.copy(message = R.string.loading)
                }

                is Listing.Listed -> {
                    place.copy(
                        message = if (listing.documents.isEmpty()) R.string.empty_folder else null,
                        documents = listing.documents,
                    )
                }

                is Listing.Failed -> {
                    place.copy(message = failureMessage(listing.failure))
                }
            }
        }

        /**
         * Whether the folder at the end of [path] can take [moving]: not where it already is, and not inside
         * itself. encri refuses both anyway (encri D15's cycle check); this keeps the button from offering them.
         */
        private fun canTake(
            path: List<EncriDocument>,
            moving: EncriDocument,
        ): Boolean = path.lastOrNull()?.id != moving.parentId && path.none { it.id == moving.id }

        private fun failureMessage(failure: Failure): Int =
            when (failure) {
                Failure.NOT_FOUND -> R.string.folder_gone
                Failure.LOCKED -> R.string.locked
                Failure.ENDED, Failure.DISCONNECTED -> R.string.session_ended
                else -> R.string.list_failed
            }
    }
}
