package com.systemhalted.encri.files.ui

import android.content.Context
import android.text.format.Formatter
import com.systemhalted.encri.files.R
import com.systemhalted.encri.files.transfer.Direction
import com.systemhalted.encri.files.transfer.Report
import com.systemhalted.encri.files.transfer.Transfer
import com.systemhalted.encri.files.transfer.Transfers
import com.systemhalted.encri.files.transfer.Why

/**
 * What the bar at the foot of the screen says about a copy (F6): how far it has got, or how it ended.
 *
 * **Names only when [namesShown]**, which is only over an open vault that is not locked: a copy the vault locked
 * under ends while the screen shows nothing of the vault (F2), and its report waits, counted but unnamed, until the
 * vault is unlocked again.
 */
object TransferWords {
    fun of(
        context: Context,
        transfer: Transfer,
        namesShown: Boolean,
    ): String =
        when (transfer) {
            is Transfer.Running -> running(context, transfer)
            is Transfer.Finished -> finished(context, transfer.report, namesShown)
        }

    /** How full the bar is, out of [PROGRESS_MAX], or null when nothing says how much there is. */
    fun progressOf(running: Transfer.Running): Int? {
        val bytes = running.bytesTotal?.takeIf { it > 0 }
        return when {
            bytes != null -> (running.bytesDone.coerceAtMost(bytes) * PROGRESS_MAX / bytes).toInt()
            running.total > 1 -> running.done * PROGRESS_MAX / running.total
            else -> null
        }
    }

    const val PROGRESS_MAX = 1000

    private fun running(
        context: Context,
        running: Transfer.Running,
    ): String {
        val doing =
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
        val done = size(context, running.bytesDone)
        val amount =
            running.bytesTotal?.takeIf { it > 0 }?.let {
                context.getString(
                    R.string.copied_bytes,
                    done,
                    size(context, it),
                )
            }
                ?: context.getString(R.string.copied_bytes_so_far, done).takeIf { running.bytesDone > 0 }
        return listOfNotNull(doing, amount, context.getString(R.string.copying_keep_open)).joinToString("\n")
    }

    private fun finished(
        context: Context,
        report: Report,
        namesShown: Boolean,
    ): String =
        when (report) {
            is Report.In -> {
                copiedIn(context, report, namesShown)
            }

            is Report.NoRoom -> {
                context.getString(
                    R.string.copy_in_no_room,
                    size(context, report.neededBytes),
                    size(context, report.usableBytes),
                )
            }

            is Report.Out -> {
                copiedOut(context, report, namesShown)
            }
        }

    private fun copiedIn(
        context: Context,
        report: Report.In,
        namesShown: Boolean,
    ): String {
        val total = report.copied + report.notCopied.size
        val lines = mutableListOf<String>()
        lines +=
            when {
                report.notCopied.isEmpty() -> {
                    context.resources.getQuantityString(R.plurals.copied_in, report.copied, report.copied)
                }

                report.copied == 0 -> {
                    context.getString(R.string.copied_in_none)
                }

                else -> {
                    context.getString(R.string.copied_in_some, report.copied, total)
                }
            }
        // A file refused on its own is named, or over a locked vault promised for later; the ones a copy did not
        // reach because it had to stop are counted, with the reason it stopped.
        val (ending, each) = report.notCopied.partition { it.why in Transfers.ENDS_A_COPY_IN }
        if (namesShown) {
            each.take(NAMED_AT_MOST).forEach { lines += line(context, it.name, it.why) }
            if (each.size > NAMED_AT_MOST) {
                val more = each.size - NAMED_AT_MOST
                lines += context.resources.getQuantityString(R.plurals.not_copied_more, more, more)
            }
        } else if (each.isNotEmpty()) {
            lines += context.getString(R.string.not_copied_unlock)
        }
        ending.firstOrNull()?.let { first ->
            val reason = context.getString(reasonOf(first.why, Direction.IN))
            lines +=
                if (report.copied == 0 && each.isEmpty()) {
                    reason
                } else {
                    context.resources.getQuantityString(R.plurals.not_copied_count, ending.size, ending.size) + " " +
                        reason
                }
        }
        if (report.leftUnfinished > 0) {
            lines +=
                context.resources.getQuantityString(
                    R.plurals.left_unfinished,
                    report.leftUnfinished,
                    report.leftUnfinished,
                )
        }
        if (report.copied > 0) lines += context.getString(R.string.originals_stay)
        return lines.joinToString("\n")
    }

    private fun copiedOut(
        context: Context,
        report: Report.Out,
        namesShown: Boolean,
    ): String =
        when (report.why) {
            null -> {
                if (namesShown) {
                    context.getString(R.string.copied_out_named, report.name)
                } else {
                    context.getString(R.string.copied_out)
                }
            }

            Why.SHORT -> {
                context.getString(R.string.copy_out_short)
            }

            else -> {
                context.getString(R.string.copy_out_failed, context.getString(reasonOf(report.why, Direction.OUT))) +
                    "\n" + context.getString(R.string.copy_out_left)
            }
        }

    private fun line(
        context: Context,
        name: String?,
        why: Why,
    ): String {
        val reason = context.getString(reasonOf(why, Direction.IN))
        return if (name == null) {
            context.getString(R.string.not_copied_unnamed, reason)
        } else {
            context.getString(R.string.not_copied_line, name, reason)
        }
    }

    /** A sentence for [why], which for a folder or a document that has gone depends on which way the copy went. */
    @Suppress("CyclomaticComplexMethod") // One branch per reason, which the compiler keeps exhaustive.
    fun reasonOf(
        why: Why,
        direction: Direction,
    ): Int =
        when (why) {
            Why.NAME_TAKEN -> R.string.reason_name_taken
            Why.BAD_NAME -> R.string.reason_bad_name
            Why.NO_NAME -> R.string.reason_no_name
            Why.NO_SPACE -> R.string.reason_no_space
            Why.LOCKED -> R.string.locked
            Why.NOT_ALLOWED -> R.string.reason_not_allowed
            Why.GONE -> if (direction == Direction.IN) R.string.reason_folder_gone else R.string.reason_gone
            Why.ACCESS_OFF -> R.string.reason_access_off
            Why.ENDED -> R.string.reason_ended
            Why.UNREADABLE -> R.string.reason_unreadable
            Why.VAULT_STOPPED -> R.string.reason_vault_stopped
            Why.DESTINATION -> R.string.reason_destination
            Why.SHORT -> R.string.copy_out_short
            Why.STOPPED -> R.string.reason_stopped
            Why.FAILED -> R.string.reason_failed
        }

    private fun size(
        context: Context,
        bytes: Long,
    ): String = Formatter.formatShortFileSize(context, bytes)

    /** A badly refused copy can name hundreds of files; the bar names this many and counts the rest. */
    private const val NAMED_AT_MOST = 10
}
