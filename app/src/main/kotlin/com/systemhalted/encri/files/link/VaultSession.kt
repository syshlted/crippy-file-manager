package com.systemhalted.encri.files.link

import android.net.Uri
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.client.EncriResult
import com.systemhalted.encri.client.NameConflict
import com.systemhalted.encri.client.Right
import com.systemhalted.encri.client.VaultEvent
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.io.OutputStream

/**
 * The part of an encri session this app uses (AGENTS.md F2).
 *
 * Narrower than the client's session on purpose: a method appears here when a screen needs it, so the
 * list of what the file manager asks encri for is this interface rather than the client's whole API.
 */
@Suppress("TooManyFunctions") // One per request a screen makes, which is what this interface lists.
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

    /**
     * A new, empty document named [name] in [parentId]. [onNameTaken] is the caller's to choose: a copy in
     * creates under a temporary name of its own, which may take a free one, and renames it to the file's own
     * name, refusing a taken one, once it is whole (F6).
     */
    suspend fun createFile(
        parentId: String,
        name: String,
        mimeType: String,
        onNameTaken: NameConflict,
    ): EncriResult<EncriDocument>

    /**
     * [documentId]'s content replaced by what [writer] writes. Saved only if [writer] returns: one that throws
     * leaves the document as it was (encri-api L11).
     */
    suspend fun write(
        documentId: String,
        writer: suspend (OutputStream) -> Unit,
    ): EncriResult<EncriDocument>

    /** [documentId]'s last change set to [modifiedMillis], which a copy in keeps from the file it copied (F6). */
    suspend fun setModified(
        documentId: String,
        modifiedMillis: Long,
    ): EncriResult<EncriDocument>

    /** [documentId]'s content, read once from the start. The caller closes it. */
    suspend fun openRead(documentId: String): EncriResult<InputStream>

    /** How many more bytes of documents the vault can take, after encri's padding and framing. */
    suspend fun usableBytes(): EncriResult<Long>

    /** Locks the vault, for every app and for encri. */
    suspend fun lock(): EncriResult<Unit>
}
