package com.systemhalted.encri.files.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import com.systemhalted.encri.files.document
import com.systemhalted.encri.files.folder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RowsTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `a folder says how much is in it`() {
        assertEquals("1 item", Rows.detail(context, folder("f", "F", children = 1), 0))
        assertEquals("3 items", Rows.detail(context, folder("f", "F", children = 3), 0))
    }

    @Test
    fun `a folder encri gave no count for is just a folder`() {
        val uncounted = folder("f", "F").copy(childCount = null)
        assertEquals("Folder", Rows.detail(context, uncounted, 0))
    }

    @Test
    fun `a document changed under a minute ago says just now, not 0 minutes ago`() {
        val now = 10 * DateUtils.DAY_IN_MILLIS
        val doc = document("d", "d.txt", sizeBytes = 2048, modifiedMillis = now - 59_000)
        val size = Formatter.formatShortFileSize(context, 2048)
        assertEquals("$size · just now", Rows.detail(context, doc, now))
        assertEquals("$size · just now", Rows.detail(context, doc.copy(modifiedMillis = now + 30_000), now))
    }

    @Test
    fun `a document says its size and when it last changed`() {
        val now = 10 * DateUtils.DAY_IN_MILLIS
        val doc = document("d", "d.txt", sizeBytes = 2048, modifiedMillis = now - 3 * DateUtils.DAY_IN_MILLIS)
        val size = Formatter.formatShortFileSize(context, 2048)
        val ago = DateUtils.getRelativeTimeSpanString(doc.modifiedMillis, now, DateUtils.MINUTE_IN_MILLIS)
        assertEquals("$size · $ago", Rows.detail(context, doc, now))
    }
}
