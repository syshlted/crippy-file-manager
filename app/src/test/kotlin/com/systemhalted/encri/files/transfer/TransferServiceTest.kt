package com.systemhalted.encri.files.transfer

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import com.systemhalted.encri.files.FakeOpener
import com.systemhalted.encri.files.FakeSession
import com.systemhalted.encri.files.TestApplication
import com.systemhalted.encri.files.folder
import com.systemhalted.encri.files.link.Opening
import com.systemhalted.encri.files.settle
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * The service a copy runs behind (AGENTS.md F8): in the foreground while the copy runs, holding the picker's grants,
 * and gone once it ends, having said how it ended only where no screen of this app said it already.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [27, 36])
class TransferServiceTest {
    private val app get() = RuntimeEnvironment.getApplication() as TestApplication
    private val session = FakeSession(folder("root", "Vault"), mapOf("root" to emptyList()))
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private lateinit var picked: Uri

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.use(FakeOpener(Opening.Open(session)))
        app.link.open()
        settle()
        picked = app.outside.add("secret-plans.txt", ByteArray(100_000))
    }

    /** Starts a copy in that waits at its write, so it is still running when the service starts. */
    private fun copyRunning() {
        session.writeGate = CompletableDeferred()
        assertTrue(app.transfers.copyIn("root", listOf(picked)))
        settle()
    }

    private fun letTheCopyFinish() {
        session.writeGate!!.complete(Unit)
        settle()
    }

    private fun started(
        intent: Intent = TransferService.intentFor(app, listOf(picked), Direction.IN),
        startId: Int = 1,
    ): ServiceController<TransferService> =
        Robolectric
            .buildService(TransferService::class.java, intent)
            .create()
            .startCommand(0, startId)
            .also { settle() }

    private fun Notification.said(): String =
        listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TEXT),
        ).joinToString("\n")

    private fun answer(): Notification? = shadowOf(manager).getNotification(SystemTransferNotice.ANSWER_ID)

    @Test
    fun `a copy runs in the foreground, and its notification counts and names nothing`() {
        copyRunning()
        val service = started().get()
        val shown = shadowOf(service).lastForegroundNotification
        assertNotNull("startForeground was called", shown)
        assertEquals(SystemTransferNotice.ONGOING_ID, shadowOf(service).lastForegroundNotificationId)
        assertEquals("Copying 1 of 1 into the vault…", shown.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertFalse(shown.said().contains("secret"))
        assertFalse(shadowOf(service).isForegroundStopped)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `once the copy ends away from the screen, the service says how and stops`() {
        copyRunning()
        val service = started().get()
        letTheCopyFinish()
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(1, shadowOf(service).stopSelfId)
        val answer = answer()!!
        assertEquals("Copied 1 file into the vault", answer.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertFalse(answer.said().contains("secret"))
    }

    @Test
    fun `a copy that ends while a screen is in front says nothing, because the bar says it`() {
        copyRunning()
        val service = started().get()
        app.transfers.screenStarted()
        letTheCopyFinish()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(answer())
    }

    @Test
    fun `a service that starts after its copy ended goes to the foreground and stops at once`() {
        app.transfers.copyIn("root", listOf(picked))
        settle()
        assertTrue(app.transfers.state.value is Transfer.Finished)
        val service = started().get()
        // Robolectric forgets the notification once it is removed, and keeps its id.
        assertEquals(
            "a start for the foreground must go there, or the platform kills the app",
            SystemTransferNotice.ONGOING_ID,
            shadowOf(service).lastForegroundNotificationId,
        )
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull("the user watched it end", answer())
    }

    @Test
    fun `Stop in the notification stops the copy, and the service goes with it`() {
        copyRunning()
        val controller = started()
        controller.get().onStartCommand(
            Intent(app, TransferService::class.java).setAction(TransferService.ACTION_STOP),
            0,
            2,
        )
        letTheCopyFinish()
        val report = (app.transfers.state.value as Transfer.Finished).report as Report.In
        assertEquals(listOf(Why.STOPPED), report.notCopied.map(NotCopied::why))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals("the latest start, or the service would stay", 2, shadowOf(controller.get()).stopSelfId)
    }

    @Test
    fun `a stop with no copy running holds nothing`() {
        val service = started(Intent(app, TransferService::class.java).setAction(TransferService.ACTION_STOP)).get()
        assertNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    @Config(sdk = [36])
    fun `the platform's dataSync budget spent stops the copy at its next block`() {
        copyRunning()
        val service = started().get()
        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        letTheCopyFinish()
        val report = (app.transfers.state.value as Transfer.Finished).report as Report.In
        assertEquals(listOf(Why.STOPPED), report.notCopied.map(NotCopied::why))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `the start hands the service a grant to read what is copied in`() {
        val second = Uri.parse("content://elsewhere/second")
        val intent = TransferService.intentFor(app, listOf(picked, second), Direction.IN)
        val clip = intent.clipData!!
        assertEquals(listOf(picked, second), (0 until clip.itemCount).map { clip.getItemAt(it).uri })
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test
    fun `the start hands the service a grant to write where a copy out goes, and to read back what it kept`() {
        val intent = TransferService.intentFor(app, listOf(picked), Direction.OUT)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
    }

    @Test
    fun `a service the platform refuses is said to be not held`() {
        val refusing =
            object : ContextWrapper(app) {
                override fun startForegroundService(service: Intent?) = error("in the background")
            }
        assertFalse(TransferService.start(refusing, listOf(picked), Direction.IN))
        assertTrue(TransferService.start(app, listOf(picked), Direction.IN))
        assertEquals(TransferService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
    }

    @Test
    fun `nothing binds to it`() {
        assertNull(started().get().onBind(Intent()))
    }
}
