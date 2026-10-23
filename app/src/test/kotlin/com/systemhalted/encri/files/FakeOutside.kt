package com.systemhalted.encri.files

import android.net.Uri
import com.systemhalted.encri.files.transfer.Outside
import com.systemhalted.encri.files.transfer.Picked
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The phone's storage outside the vault, standing in for the apps the system's picker reaches: files to copy in,
 * and the places copies out are written to.
 */
class FakeOutside : Outside {
    private class Kept(
        val picked: Picked,
        val bytes: ByteArray,
        val failAfter: Int?,
    )

    private val files = mutableMapOf<Uri, Kept>()

    /** What each copy out left at its place, once closed. */
    val written = mutableMapOf<Uri, ByteArray>()

    /** Files a copy in opened, in order. */
    val opened = mutableListOf<Uri>()

    /** Run on every read of a file being copied in, which is how a test acts while a copy is in flight. */
    var whileReading: (() -> Unit)? = null

    /** Files that are described and then refuse to open, as a revoked grant does. */
    val unopenable = mutableSetOf<Uri>()

    /** Run on every write to a place a copy out goes. */
    var whileWriting: (() -> Unit)? = null
    var refuseOutput = false

    /** When set, a place written to refuses once it holds this many bytes. */
    var refuseWriteAfter: Int? = null

    /** When set, a place written to fails to close. */
    var refuseClose = false

    /** What a place says it holds once written; by default what arrived. */
    var keptLength: (Uri) -> Long? = { written[it]?.size?.toLong() }
    var keptAsked = 0

    /** Adds a file a picker could hand over, and answers its URI. */
    @Suppress("LongParameterList") // A fixture: every field is one a test may want to set.
    fun add(
        name: String?,
        bytes: ByteArray,
        mimeType: String = "text/plain",
        size: Long? = bytes.size.toLong(),
        modified: Long? = null,
        failAfter: Int? = null,
    ): Uri {
        val uri = Uri.parse("content://elsewhere/${files.size + 1}")
        files[uri] = Kept(Picked(uri, name, mimeType, size, modified), bytes, failAfter)
        return uri
    }

    override fun describe(uri: Uri): Picked =
        files[uri]?.picked ?: Picked(uri, null, "application/octet-stream", null, null)

    override fun openInput(uri: Uri): InputStream {
        val kept = files[uri] ?: throw FileNotFoundException("no $uri")
        if (uri in unopenable) throw SecurityException("no grant on $uri")
        opened += uri
        val bytes =
            object : ByteArrayInputStream(kept.bytes) {
                override fun read(
                    into: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    whileReading?.invoke()
                    return super.read(into, offset, length)
                }
            }
        return kept.failAfter?.let { FailingAfter(bytes, it) } ?: bytes
    }

    override fun openOutput(uri: Uri): OutputStream {
        if (refuseOutput) throw IOException("the place would not open")
        val limit = refuseWriteAfter
        return object : ByteArrayOutputStream() {
            override fun write(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ) {
                whileWriting?.invoke()
                if (limit != null && size() + length > limit) throw IOException("the place is full")
                super.write(bytes, offset, length)
            }

            override fun close() {
                written[uri] = toByteArray()
                if (refuseClose) throw IOException("the place would not close")
            }
        }
    }

    override fun keptLength(uri: Uri): Long? {
        keptAsked++
        return keptLength.invoke(uri)
    }
}
