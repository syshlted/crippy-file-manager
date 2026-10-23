package com.systemhalted.encri.files.transfer

import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.systemhalted.encri.files.Vaults
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The foreground service a copy runs behind (AGENTS.md F8), so it carries on while another app is in front.
 *
 * **It runs no copy.** [Transfers] does, in the application, as it did before (F6); this keeps the process in the
 * foreground for as long as one runs, so Android neither pauses it nor stops it for being in the background, and
 * holds the picker's grants for it. A file the system's picker handed over is readable while the task of the screen
 * that received it is alive; handed to a service in its start intent, the grant lasts until the service stops for
 * that start, which `Service`'s own documentation says in those words and encri measured for its share screen
 * (encri D118). So a copy in goes on reading after its screen is closed or swiped away.
 *
 * **It follows [Transfers.state].** Started when a copy starts, it goes to the foreground at once — a service
 * started with `startForegroundService` has seconds to, or the process is killed — and stops when the copy ends,
 * having said how it ended unless a screen of this app is in front, whose bar already says it.
 *
 * Not exported, binds to nothing, and `START_NOT_STICKY`: a restarted service would have no copy to hold, because
 * the copy lived in the process that died.
 */
class TransferService : Service() {
    private val scope = MainScope()
    private lateinit var transfers: Transfers
    private lateinit var notice: TransferNotice

    /** The latest start, which is the one stopping has to name or the service stays (`stopSelf(int)`). */
    private var latestStartId = 0

    /** True once this service has seen the copy running, so it has an ending worth telling. */
    private var held = false

    override fun onCreate() {
        super.onCreate()
        transfers = (application as Vaults).transfers
        notice = SystemTransferNotice(this)
        scope.launch { transfers.state.collect(::follow) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            // From the notification. The copy hears it at its next block and ends, and the service ends with it.
            transfers.stop()
        } else {
            goToTheForeground()
        }
        // A copy that ended before this start, or a stop with nothing to stop: nothing to hold.
        if (!transfers.isRunning) end(null)
        return START_NOT_STICKY
    }

    /** The platform's `dataSync` budget, spent (API 35+): stop at the next block, keeping what finished. */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        transfers.stop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun follow(transfer: Transfer?) {
        if (transfer is Transfer.Running) {
            held = true
            notice.progressed(transfer)
        } else {
            end((transfer as? Transfer.Finished)?.report)
        }
    }

    private fun goToTheForeground() {
        val notification = notice.ongoing(transfers.state.value as? Transfer.Running)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                SystemTransferNotice.ONGOING_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(SystemTransferNotice.ONGOING_ID, notification)
        }
    }

    /** Says how the copy held ended, when it ended away from the screen, and gives the grants back. */
    private fun end(report: Report?) {
        if (held && report != null && !transfers.isWatched) notice.answered(report)
        held = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(latestStartId)
    }

    companion object {
        /** The notification's Stop. */
        const val ACTION_STOP = "com.systemhalted.encri.files.action.STOP_COPY"

        /**
         * Starts the service for a copy of [uris] going [direction], and answers whether the platform let it. It
         * refuses a foreground service to an app in the background from API 31, which a screen in front never is;
         * refused, the copy still runs, as it did before there was a service, and the bar says to stay (F6).
         */
        @Suppress("SwallowedException") // Refused is an answer: the bar says what it costs.
        fun start(
            context: Context,
            uris: List<Uri>,
            direction: Direction,
        ): Boolean =
            try {
                context.startForegroundService(intentFor(context, uris, direction)) != null
            } catch (refused: IllegalStateException) {
                false
            } catch (refused: SecurityException) {
                false
            }

        /**
         * The start intent: [uris] ride as `ClipData` with the grants the copy needs, which is how a grant this
         * process holds is handed to its own service — to read what is copied in, and to write where a copy out goes
         * and read back how much it kept (encri B102).
         */
        fun intentFor(
            context: Context,
            uris: List<Uri>,
            direction: Direction,
        ): Intent =
            Intent(context, TransferService::class.java).apply {
                if (uris.isNotEmpty()) {
                    val clip = ClipData.newRawUri("", uris.first())
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                    clipData = clip
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (direction == Direction.OUT) addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
            }
    }
}
