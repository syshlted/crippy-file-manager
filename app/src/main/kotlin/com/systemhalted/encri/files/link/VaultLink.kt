package com.systemhalted.encri.files.link

import android.net.Uri
import com.systemhalted.encri.client.EncriResult
import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.client.VaultEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/** Where the file manager stands with encri. */
sealed interface LinkState {
    /** Nothing has asked encri yet. */
    data object Idle : LinkState

    /** Asking encri for a session. */
    data object Connecting : LinkState

    /** encri gave no session, and [why] says why. Never [Opening.Open]. */
    data class Refused(
        val why: Opening,
    ) : LinkState

    /** A session, which outlives a lock (encri D120): while [locked], every request answers that it is locked. */
    data class Open(
        val session: VaultSession,
        val locked: Boolean,
    ) : LinkState
}

/**
 * The file manager's one session with encri, for the life of the process (AGENTS.md F2).
 *
 * Held by the application rather than by a screen, so a rotation does not end the session — and with it
 * every handoff the session made (encri D117), which would close a document another app is showing.
 *
 * A session encri ends — the user turned this app out, or turned access for other apps off, or encri's
 * process went — is asked for again at once, so the screen says why there is no session rather than
 * showing a vault that has gone.
 */
class VaultLink(
    private val opener: Opener,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = mutableState.asStateFlow()

    // Best effort, as encri's own notice of a change is (encri-api's PROTOCOL.md), so a slow collector loses
    // the oldest rather than holding anything up.
    private val mutableChanges =
        MutableSharedFlow<String>(extraBufferCapacity = CHANGES_BUFFERED, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Folders whose children encri said changed. */
    val changes: SharedFlow<String> = mutableChanges.asSharedFlow()

    private var asking: Job? = null

    /** Asks encri for a session, unless one is open or already being asked for. */
    fun open() {
        if (mutableState.value is LinkState.Open || asking?.isActive == true) return
        mutableState.value = LinkState.Connecting
        asking =
            scope.launch {
                when (val outcome = opener.open()) {
                    is Opening.Open -> adopt(outcome.session)
                    else -> mutableState.value = LinkState.Refused(outcome)
                }
            }
    }

    /** A URI another app may read [documentId] through, or why there is none. */
    suspend fun handOff(documentId: String): EncriResult<Uri> =
        openSession()?.handOff(documentId) ?: EncriResult.Failed(Failure.ENDED, "no session")

    /** Locks the vault. The session hears the lock and says so. */
    fun lock() {
        val session = openSession() ?: return
        scope.launch { session.lock() }
    }

    private fun openSession(): VaultSession? = (mutableState.value as? LinkState.Open)?.session

    private fun adopt(session: VaultSession) {
        mutableState.value = LinkState.Open(session, locked = false)
        scope.launch {
            session.events.takeWhile { it != VaultEvent.Ended }.collect { event ->
                when (event) {
                    VaultEvent.Locked -> setLocked(session, true)
                    VaultEvent.Unlocked -> setLocked(session, false)
                    is VaultEvent.FolderChanged -> mutableChanges.tryEmit(event.folderId)
                    VaultEvent.Ended -> Unit
                }
            }
            ended(session)
        }
    }

    private fun setLocked(
        session: VaultSession,
        locked: Boolean,
    ) {
        val open = mutableState.value as? LinkState.Open ?: return
        if (open.session === session) mutableState.value = open.copy(locked = locked)
    }

    private fun ended(session: VaultSession) {
        session.close()
        if ((mutableState.value as? LinkState.Open)?.session !== session) return
        mutableState.value = LinkState.Idle
        open()
    }

    private companion object {
        const val CHANGES_BUFFERED = 16
    }
}
