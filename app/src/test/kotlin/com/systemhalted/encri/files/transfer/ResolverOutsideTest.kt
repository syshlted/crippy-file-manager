package com.systemhalted.encri.files.transfer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileNotFoundException

/**
 * The phone's storage as [ResolverOutside] reaches it (F6), against a real `ContentProvider` standing in for the
 * app the system's picker handed a file over from, so every call goes through the resolver and an unstable client
 * as it does on a phone.
 */
@RunWith(RobolectricTestRunner::class)
class ResolverOutsideTest {
    private val context = RuntimeEnvironment.getApplication()
    private val outside = ResolverOutside(context.contentResolver)

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(KeepingProvider::class.java, AUTHORITY)
        KeepingProvider.folder = File(context.cacheDir, "kept").apply { mkdirs() }
        KeepingProvider.truncates = true
        File(KeepingProvider.folder, "trip.jpg").writeBytes(PHOTO)
    }

    @Test
    fun `a file comes with the name, type, size and date its app gives`() {
        assertEquals(
            Picked(uri("trip.jpg"), "trip.jpg", "image/jpeg", PHOTO.size.toLong(), MODIFIED),
            outside.describe(uri("trip.jpg")),
        )
    }

    @Test
    fun `a file its app will not describe is unknown rather than lost`() {
        val refused = Uri.parse("content://$AUTHORITY/refuses")
        assertEquals(Picked(refused, null, "application/octet-stream", null, null), outside.describe(refused))
    }

    @Test
    fun `a file no app serves is unknown too`() {
        val nobody = Uri.parse("content://nobody.here/x")
        assertEquals(Picked(nobody, null, "application/octet-stream", null, null), outside.describe(nobody))
        assertNull(outside.keptLength(nobody))
    }

    @Test
    fun `a file is read through its app`() {
        assertArrayEquals(PHOTO, outside.openInput(uri("trip.jpg")).use { it.readBytes() })
    }

    @Test(expected = FileNotFoundException::class)
    fun `a file its app will not open says so`() {
        outside.openInput(uri("missing.jpg"))
    }

    @Test
    fun `a copy out replaces what the place held, whole`() {
        val out = uri("out.txt")
        File(KeepingProvider.folder, "out.txt").writeBytes(ByteArray(1_000) { 7 })
        outside.openOutput(out).use { it.write(byteArrayOf(1, 2, 3)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), File(KeepingProvider.folder, "out.txt").readBytes())
        assertEquals(3L, outside.keptLength(out))
    }

    @Test
    fun `a place that will not truncate is written all the same`() {
        KeepingProvider.truncates = false
        val out = uri("new.txt")
        outside.openOutput(out).use { it.write(byteArrayOf(9)) }
        assertArrayEquals(byteArrayOf(9), File(KeepingProvider.folder, "new.txt").readBytes())
    }

    private fun uri(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")

    /** The other app: files in a folder, described, one URI it refuses to describe, and `wt` when told to. */
    class KeepingProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val name = uri.lastPathSegment ?: return null
            if (name == "refuses") throw SecurityException("not telling")
            val file = File(folder, name)
            val columns =
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )
            return MatrixCursor(columns).apply { addRow(arrayOf<Any>(name, file.length(), MODIFIED)) }
        }

        override fun getType(uri: Uri): String? =
            if (uri.lastPathSegment?.endsWith(".jpg") ==
                true
            ) {
                "image/jpeg"
            } else {
                null
            }

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor {
            if (mode == "wt" && !truncates) throw FileNotFoundException("no truncation here")
            val file = File(folder, requireNotNull(uri.lastPathSegment))
            if (mode == "r" && !file.exists()) throw FileNotFoundException("no ${file.name}")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        companion object {
            lateinit var folder: File
            var truncates = true
        }
    }

    private companion object {
        const val AUTHORITY = "test.elsewhere"
        const val MODIFIED = 1_700_000_000_000L
        val PHOTO = ByteArray(5_000) { (it % 13).toByte() }
    }
}
