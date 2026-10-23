package com.systemhalted.encri.files.transfer

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [Outside] over the phone's `ContentResolver`, which is how a file the system's picker handed over is
 * reached (AGENTS.md F6).
 *
 * **Every call goes through an unstable client**, as encri learned to (its B100). A descriptor or a query
 * through the resolver's own methods holds a *stable* reference to the other app's provider, and when that
 * app's process dies, Android kills every process holding one. A cloud app the system stops mid-copy would
 * then take this app with it, and with it the session and every document another app is reading through a
 * handoff (encri D117). Through an unstable client, a provider that dies is a read or a write that failed,
 * which the copy can report. `ForeignDocumentsTest` keeps every source to it.
 */
class ResolverOutside(
    private val resolver: ContentResolver,
) : Outside {
    override fun describe(uri: Uri): Picked {
        val type = asking(uri) { it.getType(uri) } ?: OCTET_STREAM
        // No projection: a provider names the columns it has, and one that has no last-modified time is
        // asked for nothing it would refuse.
        val described =
            asking(uri) { client ->
                client.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        Picked(
                            uri,
                            cursor.stringAt(OpenableColumns.DISPLAY_NAME)?.takeIf(String::isNotBlank),
                            type,
                            cursor.longAt(OpenableColumns.SIZE),
                            cursor.longAt(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.takeIf { it > 0 },
                        )
                    } else {
                        null
                    }
                }
            }
        return described ?: Picked(uri, null, type, null, null)
    }

    override fun openInput(uri: Uri): InputStream = ParcelFileDescriptor.AutoCloseInputStream(open(uri, "r"))

    /**
     * Opens [uri] for a copy out, asking for truncation and settling for less, as encri's export does (its
     * D78): `w` is documented as *"may or may not truncate"*, so a copy over a longer file could be followed
     * by what was there before. Not every provider has `wt`; a refusal of it falls through to `w`, and a
     * refusal of that is the real failure.
     */
    @Suppress("SwallowedException") // A refusal of `wt` is answered by asking for `w`.
    override fun openOutput(uri: Uri): OutputStream {
        val descriptor =
            try {
                open(uri, "wt")
            } catch (noTruncation: IOException) {
                open(uri, "w")
            } catch (noTruncation: UnsupportedOperationException) {
                open(uri, "w")
            }
        return ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
    }

    override fun keptLength(uri: Uri): Long? =
        asking(uri) { client ->
            client.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.longAt(OpenableColumns.SIZE) else null
            }
        }

    /** [uri] opened in [mode] through an unstable client, which the descriptor holds until it is closed. */
    private fun open(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val client =
            resolver.acquireUnstableContentProviderClient(uri)
                ?: throw FileNotFoundException("no app serves this file any more")
        val opened = runCatching { client.openFile(uri, mode) }
        val descriptor = opened.getOrNull()
        if (descriptor == null) {
            client.close()
            throw opened.exceptionOrNull()?.let(::asIoFailure) ?: FileNotFoundException("the file would not open")
        }
        return ClientHeldOpen(descriptor, client)
    }

    /**
     * What [ask] makes of the provider behind [uri], asked through an unstable client; null when there is no
     * provider or it fails in any way, which claims nothing about the file.
     */
    private fun <T> asking(
        uri: Uri,
        ask: (ContentProviderClient) -> T?,
    ): T? =
        resolver.acquireUnstableContentProviderClient(uri)?.use { client ->
            runCatching { ask(client) }.getOrNull()
        }

    private companion object {
        const val OCTET_STREAM = "application/octet-stream"

        fun Cursor.stringAt(column: String): String? =
            getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

        fun Cursor.longAt(column: String): Long? =
            getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

        /** A provider that died under the unstable client arrives as a `RemoteException`; to a copy it is I/O. */
        fun asIoFailure(failure: Throwable): Throwable =
            if (failure is RemoteException) {
                IOException(
                    "the app keeping this file stopped answering",
                    failure,
                )
            } else {
                failure
            }
    }
}

/** A descriptor that keeps its unstable client alive until it is closed, and then lets it go. */
private class ClientHeldOpen(
    descriptor: ParcelFileDescriptor,
    private val client: ContentProviderClient,
) : ParcelFileDescriptor(descriptor) {
    override fun close() {
        try {
            super.close()
        } finally {
            client.close()
        }
    }
}
