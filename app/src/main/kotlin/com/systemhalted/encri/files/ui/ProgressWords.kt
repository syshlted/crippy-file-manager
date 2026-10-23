package com.systemhalted.encri.files.ui

import android.content.Context
import android.text.format.Formatter
import com.systemhalted.encri.files.R
import com.systemhalted.encri.files.transfer.Direction
import com.systemhalted.encri.files.transfer.Transfer

/**
 * What a running copy says about how far it has got, on the bar (F6) and in its notification (F8). **It names
 * nothing**, which is what lets the notification say it on a locked screen.
 */
object ProgressWords {
    /** Which file of how many a copy in is at, or that a copy out is running. */
    fun doing(
        context: Context,
        running: Transfer.Running,
    ): String =
        when (running.direction) {
            Direction.IN -> {
                context.getString(
                    R.string.copying_in,
                    (running.done + 1).coerceAtMost(running.total),
                    running.total,
                )
            }

            Direction.OUT -> {
                context.getString(R.string.copying_out)
            }
        }

    /** How much a copy has moved — "12 MB of 40 MB", or "12 MB so far" — or null before it has moved anything. */
    fun amountOf(
        context: Context,
        running: Transfer.Running,
    ): String? {
        val done = Formatter.formatShortFileSize(context, running.bytesDone)
        val total = running.bytesTotal?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
        return when {
            total != null -> context.getString(R.string.copied_bytes, done, total)
            running.bytesDone > 0 -> context.getString(R.string.copied_bytes_so_far, done)
            else -> null
        }
    }
}
