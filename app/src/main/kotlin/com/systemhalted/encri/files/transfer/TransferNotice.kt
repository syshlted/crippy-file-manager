package com.systemhalted.encri.files.transfer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import com.systemhalted.encri.files.FilesActivity
import com.systemhalted.encri.files.R
import com.systemhalted.encri.files.ui.ProgressWords
import com.systemhalted.encri.files.ui.TransferWords

/**
 * The notification a copy runs behind (AGENTS.md F8), and the one that says how it ended.
 *
 * A seam so the service's sequencing is tested without a `NotificationManager` in the way, as encri's notices are.
 */
internal interface TransferNotice {
    /** The ongoing notification for [running], or for a copy just starting when null: what `startForeground` posts. */
    fun ongoing(running: Transfer.Running?): Notification

    /** Updates the ongoing notification, no more than once a second. */
    fun progressed(running: Transfer.Running)

    /** Posts how a copy ended, which the user reads once they have left the app. */
    fun answered(report: Report)
}

/**
 * The real one. **Counts, never a name**, and private on the lock screen: a notification is read off a locked
 * phone by whoever holds it, and the names of what is in the vault are what F2 keeps off a locked screen. Tapping
 * either opens the browsing screen, whose bar says the rest, names included once the vault is unlocked.
 *
 * One channel for both, at low importance: a copy makes no sound, and a user who silences it silences nothing else.
 */
internal class SystemTransferNotice(
    private val context: Context,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : TransferNotice {
    private val manager: NotificationManager? get() = context.getSystemService(NotificationManager::class.java)
    private var toldAt: Long? = null

    override fun ongoing(running: Transfer.Running?): Notification {
        makeChannel()
        val outgoing = running?.direction == Direction.OUT
        val builder =
            base(if (outgoing) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .addAction(stopAction())
        if (running == null) {
            builder.setContentTitle(context.getString(R.string.copying_starting)).setProgress(0, 0, true)
        } else {
            val progress = TransferWords.progressOf(running)
            builder
                .setContentTitle(ProgressWords.doing(context, running))
                .setContentText(ProgressWords.amountOf(context, running))
                .setProgress(TransferWords.PROGRESS_MAX, progress ?: 0, progress == null)
        }
        return builder.build()
    }

    override fun progressed(running: Transfer.Running) {
        // Android drops a package's notifications beyond a few a second, and a fast copy reports a mebibyte at a
        // time: once a second is as often as anybody can read it.
        val now = clock()
        val last = toldAt
        if (last != null && now - last < TELL_EVERY_MILLIS) return
        toldAt = now
        if (mayNotify()) manager?.notify(ONGOING_ID, ongoing(running))
    }

    override fun answered(report: Report) {
        if (!mayNotify()) return
        makeChannel()
        val whole = isWhole(report)
        val outgoing = report is Report.Out
        val icon =
            when {
                !whole -> android.R.drawable.stat_notify_error
                outgoing -> android.R.drawable.stat_sys_upload_done
                else -> android.R.drawable.stat_sys_download_done
            }
        val builder =
            base(icon)
                .setContentTitle(titleOf(report))
                .setAutoCancel(true)
        if (!whole) builder.setContentText(context.getString(R.string.transfer_notice_see_why))
        manager?.notify(ANSWER_ID, builder.build())
    }

    private fun titleOf(report: Report): String =
        when (report) {
            is Report.In -> {
                val total = report.copied + report.notCopied.size
                when {
                    report.notCopied.isEmpty() -> {
                        context.resources.getQuantityString(
                            R.plurals.transfer_notice_copied_in,
                            report.copied,
                            report.copied,
                        )
                    }

                    report.copied == 0 -> {
                        context.getString(R.string.transfer_notice_copied_in_none)
                    }

                    else -> {
                        context.getString(R.string.transfer_notice_copied_in_some, report.copied, total)
                    }
                }
            }

            is Report.NoRoom -> {
                context.getString(R.string.transfer_notice_copied_in_none)
            }

            is Report.Out -> {
                if (report.why == null) {
                    context.getString(R.string.transfer_notice_copied_out)
                } else {
                    context.getString(R.string.transfer_notice_copy_out_failed)
                }
            }
        }

    private fun makeChannel() {
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.transfer_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.transfer_channel_explain) },
        )
    }

    private fun base(icon: Int): Notification.Builder =
        Notification
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    REQUEST_SCREEN,
                    Intent(context, FilesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

    /** Stop, which the copy hears at its next block, as it hears the bar's (F6). */
    private fun stopAction(): Notification.Action =
        Notification.Action
            .Builder(
                Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                context.getString(R.string.transfer_stop),
                PendingIntent.getService(
                    context,
                    REQUEST_STOP,
                    Intent(context, TransferService::class.java).setAction(TransferService.ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build()

    private fun mayNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL_ID = "encri.files.copies"
        const val ONGOING_ID = 1
        const val ANSWER_ID = 2
        private const val REQUEST_SCREEN = 1
        private const val REQUEST_STOP = 2
        private const val TELL_EVERY_MILLIS = 1_000L

        /** True when a copy did all it was asked: then the notification says so and nothing more. */
        fun isWhole(report: Report): Boolean =
            when (report) {
                is Report.In -> report.notCopied.isEmpty() && report.leftUnfinished == 0
                is Report.NoRoom -> false
                is Report.Out -> report.why == null
            }

        /** Takes back how a copy ended once a screen of this app is in front, whose bar says the same. */
        fun withdrawAnswer(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(ANSWER_ID)
        }
    }
}
