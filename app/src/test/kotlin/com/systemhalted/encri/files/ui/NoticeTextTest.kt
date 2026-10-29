package com.systemhalted.encri.files.ui

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How the notices asset is drawn (F10, after encri D138): its two headings set in bold without their marks, and every
 * other line exactly as written, since a licence's words are not ours to change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NoticeTextTest {
    @Test
    fun `a heading loses its mark and is set in bold, larger than the body`() {
        val text = noticeText("# kotlinx.coroutines\nCopyright") as Spanned

        assertEquals("kotlinx.coroutines\nCopyright", text.toString())
        val bold = text.getSpans(0, text.length, StyleSpan::class.java).single()
        assertEquals(Typeface.BOLD, bold.style)
        assertEquals(0 to "kotlinx.coroutines".length, text.getSpanStart(bold) to text.getSpanEnd(bold))
        assertTrue(text.getSpans(0, text.length, RelativeSizeSpan::class.java).single().sizeChange > 1f)
    }

    @Test
    fun `a part's heading is read as one, not as a library's heading with a stray mark`() {
        val text = noticeText("## ThreeTen-Backport") as Spanned

        assertEquals("ThreeTen-Backport", text.toString())
        assertEquals(1, text.getSpans(0, text.length, StyleSpan::class.java).size)
    }

    @Test
    fun `every other line reaches the screen as written, blank lines and indentation included`() {
        val body = "Copyright (c) 2025, ZETETIC LLC\n\n    * Redistributions of source code\n#not a heading\n"

        val text = noticeText(body) as Spanned

        assertEquals(body, text.toString())
        assertEquals(0, text.getSpans(0, text.length, StyleSpan::class.java).size)
    }
}
