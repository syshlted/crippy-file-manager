package com.systemhalted.encri.files.ui

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.files.R
import kotlin.math.abs

/** The second line of a row: what a folder holds, or a document's size and when it last changed. */
object Rows {
    fun detail(
        context: Context,
        document: EncriDocument,
        nowMillis: Long,
    ): String =
        if (document.isFolder) {
            document.childCount?.let { context.resources.getQuantityString(R.plurals.folder_items, it, it) }
                ?: context.getString(R.string.folder)
        } else {
            context.getString(
                R.string.document_line,
                Formatter.formatShortFileSize(context, document.sizeBytes),
                ago(context, document.modifiedMillis, nowMillis),
            )
        }

    /**
     * When a document last changed, as the platform words it — except under a minute either way, which the
     * platform words "0 minutes ago" on some ROMs, as encri found on the A20 for its own screens (its B98).
     */
    private fun ago(
        context: Context,
        thenMillis: Long,
        nowMillis: Long,
    ): CharSequence =
        if (abs(nowMillis - thenMillis) < DateUtils.MINUTE_IN_MILLIS) {
            context.getString(R.string.just_now)
        } else {
            DateUtils.getRelativeTimeSpanString(thenMillis, nowMillis, DateUtils.MINUTE_IN_MILLIS)
        }
}
