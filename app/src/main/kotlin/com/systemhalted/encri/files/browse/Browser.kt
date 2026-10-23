package com.systemhalted.encri.files.browse

import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.client.EncriResult
import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.files.link.LinkState
import com.systemhalted.encri.files.link.VaultLink
import com.systemhalted.encri.files.link.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.Collator

/** What the folder being looked at holds, as far as is known. */
sealed interface Listing {
    /** Nothing to show: there is no session, or the vault is locked. */
    data object Waiting : Listing

    data object Loading : Listing

    /** The folder's children, folders first ([Ordering]). */
    data class Listed(
        val documents: List<EncriDocument>,
    ) : Listing

    data class Failed(
        val failure: Failure,
    ) : Listing
}

/**
 * Where the user is in the vault and what is there (AGENTS.md F2).
 *
 * The place is kept for the process, beside the [VaultLink], so a rotation keeps it. It is kept across a
 * lock too, and an unlock lists the same folder again, but nothing is listed while the vault is locked:
 * a user who locks the vault expects its names off the screen, as encri's own file picker takes them off
 * (encri D17).
 */
class Browser(
    private val link: VaultLink,
    private val scope: CoroutineScope,
) {
    private val mutablePath = MutableStateFlow<List<EncriDocument>>(emptyList())

    /** The folders from where the user was let in down to the one being looked at. */
    val path: StateFlow<List<EncriDocument>> = mutablePath.asStateFlow()

    private val mutableListing = MutableStateFlow<Listing>(Listing.Waiting)
    val listing: StateFlow<Listing> = mutableListing.asStateFlow()

    private var session: VaultSession? = null
    private var locked = false
    private var loading: Job? = null

    init {
        scope.launch { link.state.collect(::follow) }
        scope.launch { link.changes.collect { folderId -> if (folderId == path.value.lastOrNull()?.id) reload() } }
    }

    /** Goes into [folder], a child of the folder being looked at. */
    fun enter(folder: EncriDocument) {
        require(folder.isFolder) { "${folder.id} is not a folder" }
        mutablePath.value = mutablePath.value + folder
        reload()
    }

    /** Goes to the parent folder; false when the user is already where they were let in. */
    fun up(): Boolean {
        val current = mutablePath.value
        if (current.size < 2) return false
        mutablePath.value = current.dropLast(1)
        reload()
        return true
    }

    /** Lists the folder being looked at again. */
    fun reload() {
        loading?.cancel()
        val open = session?.takeIf { !locked }
        val folder = mutablePath.value.lastOrNull()
        if (open == null || folder == null) {
            mutableListing.value = Listing.Waiting
            return
        }
        mutableListing.value = Listing.Loading
        loading =
            scope.launch {
                mutableListing.value =
                    when (val listed = open.list(folder.id)) {
                        is EncriResult.Ok -> Listing.Listed(Ordering.of(listed.value))
                        is EncriResult.Failed -> Listing.Failed(listed.failure)
                    }
            }
    }

    private fun follow(state: LinkState) {
        val open = state as? LinkState.Open
        if (open == null) {
            session = null
            reload()
            return
        }
        val fresh = open.session !== session
        val unlocked = locked && !open.locked
        session = open.session
        locked = open.locked
        // A session reopened after encri restarted keeps the user's place when it starts where the old one
        // did; one rooted elsewhere — the user changed what this app may see — starts at its root.
        if (fresh && mutablePath.value.firstOrNull()?.id != open.session.root.id) {
            mutablePath.value = listOf(open.session.root)
        }
        if (fresh || unlocked || open.locked) reload()
    }
}

/** The order a folder's children are shown in: folders first, then by name as this locale sorts it. */
object Ordering {
    fun of(documents: List<EncriDocument>): List<EncriDocument> {
        val names = Collator.getInstance()
        val byName = Comparator<EncriDocument> { a, b -> names.compare(a.name, b.name) }
        return documents.sortedWith(compareBy<EncriDocument> { !it.isFolder }.then(byName))
    }
}
