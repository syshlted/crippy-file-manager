package com.systemhalted.encri.files

import android.net.Uri
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.client.EncriResult
import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.client.Right
import com.systemhalted.encri.client.VaultEvent
import com.systemhalted.encri.files.link.Asked
import com.systemhalted.encri.files.link.Opener
import com.systemhalted.encri.files.link.Opening
import com.systemhalted.encri.files.link.VaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow

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
    ): EncriResult<EncriDocument> =
        change("rename $documentId $name") {
            val (siblings, index) = locate(documentId)
            siblings[index].copy(name = name).also { siblings[index] = it }
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
