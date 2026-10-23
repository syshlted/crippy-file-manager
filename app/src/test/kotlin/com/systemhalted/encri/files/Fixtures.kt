package com.systemhalted.encri.files

import android.net.Uri
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.client.EncriResult
import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.client.NameConflict
import com.systemhalted.encri.client.Right
import com.systemhalted.encri.client.VaultEvent
import com.systemhalted.encri.files.link.Asked
import com.systemhalted.encri.files.link.Opener
import com.systemhalted.encri.files.link.Opening
import com.systemhalted.encri.files.link.VaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

const val DIRECTORY = "vnd.android.document/directory"

fun folder(
    id: String,
    name: String,
    parentId: String? = null,
    children: Int = 0,
) = EncriDocument(id, parentId, name, DIRECTORY, true, 0, 0, 0, children, false)

@Suppress("LongParameterList") // A fixture: every field is one a test may want to set.
fun document(
    id: String,
    name: String,
    parentId: String? = null,
    mimeType: String = "text/plain",
    sizeBytes: Long = 0,
    modifiedMillis: Long = 0,
    thumbnailable: Boolean = false,
) = EncriDocument(id, parentId, name, mimeType, false, sizeBytes, 0, modifiedMillis, null, thumbnailable)

/**
 * A session over an in-memory tree, standing in for encri's. Every request is recorded, [failNext] makes
 * the next listing fail as encri would, and [failChanges] makes every change fail.
 */
class FakeSession(
    override val root: EncriDocument,
    tree: Map<String, List<EncriDocument>>,
    override val rights: Set<Right> = Asked.RIGHTS,
) : VaultSession {
    override val events = MutableSharedFlow<VaultEvent>(extraBufferCapacity = 16)

    /** Each folder's children, which the changes below change. */
    val tree: MutableMap<String, MutableList<EncriDocument>> =
        tree.mapValues { it.value.toMutableList() }.toMutableMap()

    /** Every change asked for, in words: "create root Trips", "rename notes notes 2", and so on. */
    val changes = mutableListOf<String>()
    var failChanges: Failure? = null

    val listed = mutableListOf<String>()
    val handedOff = mutableListOf<String>()
    var locks = 0
    var closed = false
    var failNext: Failure? = null
    var handOffAnswer: EncriResult<Uri>? = null

    /** Every preview asked for, as (document, width, height). */
    val previewed = mutableListOf<Triple<String, Int, Int>>()
    var previewAnswer: (String) -> EncriResult<ByteArray> = { EncriResult.Ok(byteArrayOf(1, 2, 3)) }

    /** When set, a preview waits for it, so a test can cancel one in flight. */
    var previewGate: CompletableDeferred<Unit>? = null
    var previewsCancelled = 0

    override suspend fun list(folderId: String): EncriResult<List<EncriDocument>> {
        listed += folderId
        failNext?.let {
            failNext = null
            return EncriResult.Failed(it, "told to")
        }
        return this.tree[folderId]?.let { EncriResult.Ok(it.toList()) }
            ?: EncriResult.Failed(Failure.NOT_FOUND, "no $folderId")
    }

    override suspend fun thumbnail(
        documentId: String,
        width: Int,
        height: Int,
    ): EncriResult<ByteArray> {
        previewed += Triple(documentId, width, height)
        try {
            previewGate?.await()
        } catch (cancelled: CancellationException) {
            previewsCancelled++
            throw cancelled
        }
        return previewAnswer(documentId)
    }

    override suspend fun handOff(documentId: String): EncriResult<Uri> {
        handedOff += documentId
        return handOffAnswer ?: EncriResult.Ok(Uri.parse("content://com.systemhalted.encri.open/$documentId"))
    }

    override suspend fun createFolder(
        parentId: String,
        name: String,
    ): EncriResult<EncriDocument> =
        change("create $parentId $name") {
            folder("new-$name", name, parentId).also {
                this.tree.getValue(parentId) += it
                this.tree[it.id] = mutableListOf()
            }
        }

    override suspend fun rename(
        documentId: String,
        name: String,
    ): EncriResult<EncriDocument> {
        val (siblings, index) = locate(documentId)
        if (siblings.any { it.name == name && it.id != documentId }) {
            changes += "rename $documentId $name"
            return EncriResult.Failed(Failure.NAME_TAKEN, "taken")
        }
        // encri stamps a rename as a change, as its catalog does (encri's Catalog.rename).
        return change("rename $documentId $name") {
            siblings[index].copy(name = name, modifiedMillis = RENAMED_AT).also { siblings[index] = it }
        }
    }

    override suspend fun move(
        documentId: String,
        parentId: String,
    ): EncriResult<EncriDocument> =
        change("move $documentId $parentId") {
            val (siblings, index) = locate(documentId)
            siblings.removeAt(index).copy(parentId = parentId).also { this.tree.getValue(parentId) += it }
        }

    override suspend fun delete(documentId: String): EncriResult<Unit> =
        change("delete $documentId") {
            val (siblings, index) = locate(documentId)
            siblings.removeAt(index).let { }
        }

    /** Each document's content, which [write] replaces and [openRead] reads. */
    val contents = mutableMapOf<String, ByteArray>()

    /** Every time set, as document to milliseconds. */
    val modified = mutableMapOf<String, Long>()
    var usable = Long.MAX_VALUE
    var failCreate: Failure? = null

    /** When set, encri refuses a write once it holds this many bytes, with this reason, as a full disk does. */
    var refuseWriteAfter: Pair<Int, Failure>? = null

    /** When set, a write waits for it before taking any bytes, so a test can look at a copy in flight. */
    var writeGate: CompletableDeferred<Unit>? = null

    /** When set, a document opened for reading fails after this many bytes, as a lock does. */
    var failReadAfter: Int? = null
    private var made = 0

    @Suppress("ReturnCount") // One per answer encri gives.
    override suspend fun createFile(
        parentId: String,
        name: String,
        mimeType: String,
        onNameTaken: NameConflict,
    ): EncriResult<EncriDocument> {
        changes += "createFile $parentId $name"
        failCreate?.let { return EncriResult.Failed(it, "told to") }
        val siblings = this.tree[parentId] ?: return EncriResult.Failed(Failure.NOT_FOUND, "no $parentId")
        val taken = siblings.any { it.name == name }
        if (taken && onNameTaken == NameConflict.REFUSE) return EncriResult.Failed(Failure.NAME_TAKEN, "taken")
        val free = if (taken) "$name (1)" else name
        return EncriResult.Ok(document("file-${++made}", free, parentId, mimeType).also { siblings += it })
    }

    @Suppress("TooGenericExceptionCaught") // encri saves nothing whatever stops the writer, as the client says.
    override suspend fun write(
        documentId: String,
        writer: suspend (OutputStream) -> Unit,
    ): EncriResult<EncriDocument> {
        changes += "write $documentId"
        writeGate?.await()
        val refusal = refuseWriteAfter
        val held = ByteArrayOutputStream()
        val out =
            object : OutputStream() {
                override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    if (refusal != null && held.size() + length > refusal.first) throw IOException("encri refused")
                    held.write(bytes, offset, length)
                }
            }
        try {
            writer(out)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (stopped: Throwable) {
            val failure = if (refusal != null && stopped is IOException) refusal.second else Failure.CANCELLED
            return EncriResult.Failed(failure, "not saved")
        }
        contents[documentId] = held.toByteArray()
        val (siblings, index) = locate(documentId)
        return EncriResult.Ok(siblings[index].copy(sizeBytes = held.size().toLong()).also { siblings[index] = it })
    }

    override suspend fun setModified(
        documentId: String,
        modifiedMillis: Long,
    ): EncriResult<EncriDocument> {
        modified[documentId] = modifiedMillis
        val (siblings, index) = locate(documentId)
        return EncriResult.Ok(siblings[index].copy(modifiedMillis = modifiedMillis).also { siblings[index] = it })
    }

    @Suppress("ReturnCount") // One per answer encri gives.
    override suspend fun openRead(documentId: String): EncriResult<InputStream> {
        failNext?.let {
            failNext = null
            return EncriResult.Failed(it, "told to")
        }
        val bytes = contents[documentId] ?: return EncriResult.Failed(Failure.NOT_FOUND, "no $documentId")
        val limit = failReadAfter ?: return EncriResult.Ok(ByteArrayInputStream(bytes))
        return EncriResult.Ok(FailingAfter(ByteArrayInputStream(bytes), limit))
    }

    override suspend fun usableBytes(): EncriResult<Long> = EncriResult.Ok(usable)

    private fun locate(documentId: String): Pair<MutableList<EncriDocument>, Int> =
        this.tree.values.first { children -> children.any { it.id == documentId } }.let { children ->
            children to children.indexOfFirst { it.id == documentId }
        }

    private fun <T> change(
        said: String,
        made: () -> T,
    ): EncriResult<T> {
        changes += said
        return failChanges?.let { EncriResult.Failed(it, "told to") } ?: EncriResult.Ok(made())
    }

    override suspend fun lock(): EncriResult<Unit> {
        locks++
        return EncriResult.Ok(Unit)
    }

    override fun close() {
        closed = true
    }
}

/** The time [FakeSession] stamps a rename with, standing in for the clock encri reads. */
const val RENAMED_AT = 99_999L

/** A stream that reads [limit] bytes of [inner] and then fails, as a provider or a vault that stops does. */
class FailingAfter(
    private val inner: InputStream,
    private val limit: Int,
) : InputStream() {
    private var given = 0

    override fun read(): Int {
        if (given >= limit) throw IOException("stopped after $limit bytes")
        return inner.read().also { if (it >= 0) given++ }
    }

    override fun read(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (given >= limit) throw IOException("stopped after $limit bytes")
        val read = inner.read(bytes, offset, minOf(length, limit - given))
        if (read > 0) given += read
        return read
    }
}

/** Answers each [open] with the next of [answers], and the last one for ever after. */
class FakeOpener(
    vararg answers: Opening,
) : Opener {
    private val queue = ArrayDeque(answers.toList())
    var opened = 0

    override suspend fun open(): Opening {
        opened++
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }
}
