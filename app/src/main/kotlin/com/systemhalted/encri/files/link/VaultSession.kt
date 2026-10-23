package com.systemhalted.encri.files.link

import android.net.Uri
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.client.EncriResult
import com.systemhalted.encri.client.VaultEvent
import kotlinx.coroutines.flow.Flow

/**
 * The part of an encri session this app uses (AGENTS.md F2).
 *
 * Narrower than the client's session on purpose: a method appears here when a screen needs it, so the
 * list of what the file manager asks encri for is this interface rather than the client's whole API.
 */
interface VaultSession : AutoCloseable {
    /** The folder the user let this app into, which is where browsing starts and above which it cannot go. */
    val root: EncriDocument

    /** Locks, unlocks, folders that changed, and the session ending. */
    val events: Flow<VaultEvent>

    /** Every child of [folderId], in encri's order. */
    suspend fun list(folderId: String): EncriResult<List<EncriDocument>>

    /** A `content://` URI that lets one other app read [documentId] (encri D117). */
    suspend fun handOff(documentId: String): EncriResult<Uri>

    /** Locks the vault, for every app and for encri. */
    suspend fun lock(): EncriResult<Unit>
}
