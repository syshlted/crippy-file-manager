package com.systemhalted.encri.files.link

import android.net.Uri
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.client.EncriResult
import com.systemhalted.encri.client.Right
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

    /** What the user let this app do, which lasts as long as the session (encri D120). */
    val rights: Set<Right>

    /** Locks, unlocks, folders that changed, and the session ending. */
    val events: Flow<VaultEvent>

    /** Every child of [folderId], in encri's order. */
    suspend fun list(folderId: String): EncriResult<List<EncriDocument>>

    /**
     * A preview of [documentId] that encri made and encoded, no larger than [width] by [height] (encri D112,
     * D121). Only for a document encri marks thumbnailable.
     */
    suspend fun thumbnail(
        documentId: String,
        width: Int,
        height: Int,
    ): EncriResult<ByteArray>

    /** A `content://` URI that lets one other app read [documentId] (encri D117). */
    suspend fun handOff(documentId: String): EncriResult<Uri>

    /** A new folder named [name] in [parentId]. A name already there is refused, never changed. */
    suspend fun createFolder(
        parentId: String,
        name: String,
    ): EncriResult<EncriDocument>

    /** [documentId] renamed to [name]. A name already there is refused, never changed. */
    suspend fun rename(
        documentId: String,
        name: String,
    ): EncriResult<EncriDocument>

    /** [documentId] moved into [parentId]. A name already there is refused, never changed. */
    suspend fun move(
        documentId: String,
        parentId: String,
    ): EncriResult<EncriDocument>

    /** [documentId] deleted, a folder with everything in it. */
    suspend fun delete(documentId: String): EncriResult<Unit>

    /** Locks the vault, for every app and for encri. */
    suspend fun lock(): EncriResult<Unit>
}
