package com.systemhalted.encri.files.link

import android.content.Context
import android.net.Uri
import com.systemhalted.encri.client.ConnectResult
import com.systemhalted.encri.client.Encri
import com.systemhalted.encri.client.EncriConnection
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.client.EncriResult
import com.systemhalted.encri.client.EncriSession
import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.client.NameConflict
import com.systemhalted.encri.client.Right
import com.systemhalted.encri.client.SessionOutcome
import com.systemhalted.encri.client.VaultEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The [Opener] that asks the encri installed on this phone, through encri-client (encri D112).
 *
 * One connection for the life of the process, opened on first use and opened again when encri's process
 * has gone. Trust is the client's default: an encri signed by this app's own key, which is how encri and
 * its file manager share one (encri D115). Debug builds made on one machine share its debug key the same
 * way.
 */
class ClientOpener(
    private val context: Context,
) : Opener {
    private val guard = Mutex()
    private var connection: EncriConnection? = null

    /** Why the last [connect] gave no connection. */
    private var refusal: Opening = Opening.Unavailable

    override suspend fun open(): Opening =
        guard.withLock {
            val held = connection?.takeIf { it.isConnected } ?: connect() ?: return@withLock refusal
            when (val outcome = held.openSession()) {
                is SessionOutcome.Opened -> Opening.Open(ClientSession(outcome.session))
                else -> Opening.refusalOf(outcome) ?: Opening.Unavailable
            }
        }

    private suspend fun connect(): EncriConnection? =
        when (val result = Encri.connect(context.applicationContext)) {
            is ConnectResult.Connected -> {
                result.connection.also { connection = it }
            }

            else -> {
                refusal = Opening.refusalOf(result) ?: Opening.Unavailable
                null
            }
        }
}

/** A real session, as the file manager uses it. */
private class ClientSession(
    private val session: EncriSession,
) : VaultSession {
    override val root: EncriDocument get() = session.grant.scope

    override val rights: Set<Right> get() = session.grant.rights

    override val events: Flow<VaultEvent> get() = session.events

    override suspend fun list(folderId: String): EncriResult<List<EncriDocument>> = session.list(folderId)

    override suspend fun thumbnail(
        documentId: String,
        width: Int,
        height: Int,
    ): EncriResult<ByteArray> = session.thumbnail(documentId, width, height)

    override suspend fun handOff(documentId: String): EncriResult<Uri> {
        val handed = session.handOff(listOf(documentId))
        val uri = (handed as? EncriResult.Ok)?.value?.singleOrNull()
        return when {
            uri != null -> EncriResult.Ok(uri)
            handed is EncriResult.Failed -> handed
            else -> EncriResult.Failed(Failure.FAILED, "encri handed back a URI count other than one")
        }
    }

    override suspend fun createFolder(
        parentId: String,
        name: String,
    ): EncriResult<EncriDocument> = session.createFolder(parentId, name, NameConflict.REFUSE)

    override suspend fun rename(
        documentId: String,
        name: String,
    ): EncriResult<EncriDocument> = session.rename(documentId, name, NameConflict.REFUSE)

    override suspend fun move(
        documentId: String,
        parentId: String,
    ): EncriResult<EncriDocument> = session.move(documentId, parentId, NameConflict.REFUSE)

    override suspend fun delete(documentId: String): EncriResult<Unit> = session.delete(documentId)

    override suspend fun lock(): EncriResult<Unit> = session.lock()

    override fun close() = session.close()
}
