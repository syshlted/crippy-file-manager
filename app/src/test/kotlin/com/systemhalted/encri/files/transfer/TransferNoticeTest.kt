package com.systemhalted.encri.files.transfer

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import com.systemhalted.encri.files.TestApplication
import com.systemhalted.encri.files.ui.TransferWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What a copy's notifications say (AGENTS.md F8): how far, how it ended, and never a name. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [27, 36])
class TransferNoticeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private var now = 10_000L
    private val notice by lazy { SystemTransferNotice(app) { now } }

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun Notification.title(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun Notification.text(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun answered(report: Report): Notification? {
        notice.answered(report)
        return shadowOf(manager).getNotification(SystemTransferNotice.ANSWER_ID)
    }

    @Test
    fun `the ongoing notification says which file, how much, and offers Stop`() {
        val shown = notice.ongoing(Transfer.Running(Direction.IN, 1, 3, 2_000_000, 4_000_000))
        assertEquals("Copying 2 of 3 into the vault…", shown.title())
        assertEquals("2.0 MB of 4.0 MB", shown.text())
        assertEquals(TransferWords.PROGRESS_MAX / 2, shown.extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(shown.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals(Notification.VISIBILITY_PRIVATE, shown.visibility)
        val stop = shown.actions.single()
        assertEquals("Stop", stop.title.toString())
        val sent = shadowOf(stop.actionIntent)
        assertTrue(sent.isService)
        assertEquals(TransferService.ACTION_STOP, sent.savedIntent.action)
        assertEquals(TransferService::class.java.name, sent.savedIntent.component!!.className)
    }

    @Test
    fun `before a copy says which way it goes, the notification spins`() {
        val shown = notice.ongoing(null)
        assertEquals("Copying…", shown.title())
        assertTrue(shown.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test
    fun `a copy out does not say what it is copying`() {
        val shown = notice.ongoing(Transfer.Running(Direction.OUT, 0, 1, 0, null))
        assertEquals("Copying out of the vault…", shown.title())
        assertNull("nothing has moved yet, and there is nothing else to say", shown.text())
    }

    @Test
    fun `its channel is quiet, and says what it is for`() {
        notice.ongoing(null)
        val channel = manager.getNotificationChannel(SystemTransferNotice.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals("Copies", channel.name.toString())
    }

    @Test
    fun `progress is told at most once a second`() {
        fun told(): String? = shadowOf(manager).getNotification(SystemTransferNotice.ONGOING_ID)?.text()

        notice.progressed(Transfer.Running(Direction.IN, 0, 1, 1_000_000, 4_000_000))
        assertEquals("1.0 MB of 4.0 MB", told())
        now += 999
        notice.progressed(Transfer.Running(Direction.IN, 0, 1, 2_000_000, 4_000_000))
        assertEquals("1.0 MB of 4.0 MB", told())
        now += 1
        notice.progressed(Transfer.Running(Direction.IN, 0, 1, 3_000_000, 4_000_000))
        assertEquals("3.0 MB of 4.0 MB", told())
    }

    @Test
    fun `a copy in that did all it was asked says how many, and nothing more`() {
        val answer = answered(Report.In(3, emptyList(), 0))!!
        assertEquals("Copied 3 files into the vault", answer.title())
        assertNull(answer.text())
        assertTrue(answer.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals("Copied 1 file into the vault", answered(Report.In(1, emptyList(), 0))!!.title())
    }

    @Test
    fun `a copy in that left files behind says how many of how many, and names none`() {
        val left = listOf(NotCopied("secret-plans.txt", Why.NAME_TAKEN))
        val some = answered(Report.In(2, left, 0))!!
        assertEquals("Copied 2 of 3 files into the vault", some.title())
        assertEquals("Open Encri Files to see what happened.", some.text())
        assertFalse(some.title()!!.contains("secret") || some.text()!!.contains("secret"))
        assertEquals("Nothing was copied into the vault", answered(Report.In(0, left, 0))!!.title())
        assertEquals("Nothing was copied into the vault", answered(Report.NoRoom(10, 5))!!.title())
        assertEquals(
            "Open Encri Files to see what happened.",
            answered(Report.In(1, emptyList(), 1))!!.text(),
        )
    }

    @Test
    fun `a copy out says whether it finished, and never what it was`() {
        val whole = answered(Report.Out("secret-plans.txt", null))!!
        assertEquals("Copied a document out of the vault", whole.title())
        assertNull(whole.text())
        val failed = answered(Report.Out("secret-plans.txt", Why.DESTINATION))!!
        assertEquals("A document was not copied out of the vault", failed.title())
        assertFalse(failed.title()!!.contains("secret") || failed.text()!!.contains("secret"))
    }

    @Test
    fun `an answer is taken back once a screen says it`() {
        answered(Report.In(1, emptyList(), 0))
        SystemTransferNotice.withdrawAnswer(app)
        assertNull(shadowOf(manager).getNotification(SystemTransferNotice.ANSWER_ID))
    }

    @Test
    @Config(sdk = [36])
    fun `with notifications refused, nothing is posted, and the service still has its notification`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val shown = notice.ongoing(Transfer.Running(Direction.IN, 0, 1, 0, null))
        notice.progressed(Transfer.Running(Direction.IN, 0, 1, 1, null))
        notice.answered(Report.In(1, emptyList(), 0))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals("Copying 1 of 1 into the vault…", shown.title())
    }
}
