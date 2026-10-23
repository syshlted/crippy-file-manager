package com.systemhalted.encri.files.ui

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.files.R

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
                DateUtils.getRelativeTimeSpanString(document.modifiedMillis, nowMillis, DateUtils.MINUTE_IN_MILLIS),
            )
        }
}
