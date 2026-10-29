package com.systemhalted.encri.files.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/** The asset the notices screen shows, which `:app:checkThirdPartyNotices` holds to the build (F10). */
const val NOTICES_ASSET = "third-party-notices.txt"

/**
 * The notices as the screen draws them: [source] line for line, with its two kinds of heading set in bold.
 * encri's, for encri's reason (encri D138).
 *
 * **One paragraph to a line, so the screen does the wrapping.** The licences were written at eighty
 * columns, and a phone narrower than that would break every line twice. A line opening `# ` names a
 * library or a licence and `## ` a part of one; the marks are dropped and the words kept. Nothing else
 * is interpreted, so a licence's own text reaches the screen exactly as the asset holds it.
 */
fun noticeText(source: String): CharSequence =
    SpannableStringBuilder().apply {
        source.lines().forEachIndexed { index, line ->
            if (index > 0) append('\n')
            val heading = HEADINGS.firstOrNull { line.startsWith(it.first) }
            if (heading == null) {
                append(line)
            } else {
                val start = length
                append(line.removePrefix(heading.first))
                setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(heading.second), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

/** Each heading's mark and its size against the body, the longer mark first so `## ` is not read as `# `. */
private val HEADINGS = listOf("## " to PART_SIZE, "# " to ENTRY_SIZE)

/** A library's or a licence's heading, against the body. */
private const val ENTRY_SIZE = 1.3f

/** A part of one, such as the ThreeTen-Backport code the Kotlin standard library carries. */
private const val PART_SIZE = 1.1f
