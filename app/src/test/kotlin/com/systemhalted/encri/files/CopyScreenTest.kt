package com.systemhalted.encri.files

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.WindowManager
import com.systemhalted.encri.client.Right
import com.systemhalted.encri.client.VaultEvent
import com.systemhalted.encri.files.link.Opening
import com.systemhalted.encri.files.transfer.Report
import com.systemhalted.encri.files.transfer.SystemTransferNotice
import com.systemhalted.encri.files.transfer.TransferService
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowAlertDialog

/** Copying files into the vault and out of it from the browsing screen (AGENTS.md F6). */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [27, 36])
class CopyScreenTest {
    private val root = folder("root", "Vault", children = 2)
    private val photos = folder("photos", "Photos", "root")
    private val notes = document("notes", "notes.txt", "root", sizeBytes = 5)
    private val session =
        FakeSession(root, mapOf("root" to listOf(notes, photos), "photos" to emptyList())).apply {
            contents["notes"] = byteArrayOf(1, 2, 3, 4, 5)
        }

    private val outside get() = (RuntimeEnvironment.getApplication() as TestApplication).outside

    private fun dialog(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog

    private fun AlertDialog.choose(label: String) {
        val items = shadowOf(this).items.map(CharSequence::toString)
        shadowOf(this).clickOnItem(items.indexOf(label))
        settle()
    }

    /** Starts a copy in of [uris] from the menu, and hands them back from the picker. */
    private fun Activity.copyIn(vararg uris: Uri) {
        onOptionsItemSelected(RoboMenuItem(FilesActivity.MENU_COPY_IN))
        val asked = shadowOf(this).nextStartedActivityForResult
        val chosen =
            Intent().apply {
                clipData =
                    ClipData.newRawUri("", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            }
        shadowOf(this).receiveResult(asked.intent, Activity.RESULT_OK, chosen)
        settle()
    }

    @Test
    fun `files are copied in from the menu, and the bar says so until it is read`() {
        val activity = start(Opening.Open(session)).get()
        activity.copyIn(outside.add("a.txt", byteArrayOf(1)), outside.add("b.txt", byteArrayOf(2)))
        assertEquals(listOf("Photos", "a.txt", "b.txt", "notes.txt"), activity.rowNames())
        assertEquals(View.VISIBLE, activity.transferBar().visibility)
        assertTrue(activity.transferText().startsWith("Copied 2 files into the vault."))
        assertEquals("OK", activity.transferButton().text.toString())
        activity.transferButton().performClick()
        settle()
        assertEquals(View.GONE, activity.transferBar().visibility)
    }

    @Test
    fun `the picker is asked for any number of files of any type, to read`() {
        val activity = start(Opening.Open(session)).get()
        activity.onOptionsItemSelected(RoboMenuItem(FilesActivity.MENU_COPY_IN))
        val asked = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, asked.action)
        assertEquals("*/*", asked.type)
        assertTrue(asked.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertTrue(Intent.CATEGORY_OPENABLE in asked.categories)
    }

    @Test
    fun `files go into the folder the user was in when they asked`() {
        val activity = start(Opening.Open(session)).get()
        activity.tap(0)
        activity.copyIn(outside.add("beach.jpg", byteArrayOf(1)))
        assertEquals(listOf("beach.jpg"), activity.rowNames())
        assertEquals(listOf("createFile photos (unfinished) beach.jpg"), session.changes.take(1))
    }

    @Test
    fun `a picker given up copies nothing`() {
        val activity = start(Opening.Open(session)).get()
        activity.onOptionsItemSelected(RoboMenuItem(FilesActivity.MENU_COPY_IN))
        val asked = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(asked.intent, Activity.RESULT_CANCELED, null)
        settle()
        assertTrue(session.changes.isEmpty())
        assertEquals(View.GONE, activity.transferBar().visibility)
    }

    @Test
    fun `copying in is offered only where files can be made`() {
        assertTrue(
            start(Opening.Open(session))
                .get()
                .menu()
                .findItem(FilesActivity.MENU_COPY_IN)
                .isVisible,
        )
        val readOnly = FakeSession(root, mapOf("root" to listOf(notes)), setOf(Right.READ, Right.CREATE))
        assertFalse(
            start(Opening.Open(readOnly))
                .get()
                .menu()
                .findItem(FilesActivity.MENU_COPY_IN)
                .isVisible,
        )
    }

    @Test
    @Config(sdk = [27])
    @Suppress("DEPRECATION")
    fun `while a copy runs the bar can stop it, nothing else is copied, and back at the top keeps the app`() {
        session.writeGate = CompletableDeferred()
        val activity = start(Opening.Open(session)).get()
        activity.copyIn(outside.add("a.txt", ByteArray(100_000)), outside.add("b.txt", byteArrayOf(2)))
        assertTrue(activity.transferText().startsWith("Copying 1 of 2 into the vault…"))
        assertEquals("Stop", activity.transferButton().text.toString())
        assertFalse(activity.menu().findItem(FilesActivity.MENU_COPY_IN).isVisible)
        activity.onBackPressed()
        assertFalse("a copy still reading would lose the picker's grant", activity.isFinishing)
        activity.transferButton().performClick()
        session.writeGate!!.complete(Unit)
        settle()
        assertEquals("Nothing was copied into the vault.\nIt was stopped.", activity.transferText())
        assertEquals(listOf("Photos", "notes.txt"), activity.rowNames())
        activity.onBackPressed()
        assertTrue("with no copy running, back at the top leaves as before", activity.isFinishing)
    }

    @Test
    fun `an ending over a locked vault names nothing until it is unlocked`() {
        val activity = start(Opening.Open(session)).get()
        activity.copyIn(outside.add("notes.txt", byteArrayOf(1)))
        assertTrue(activity.transferText().contains("“notes.txt”"))
        session.events.tryEmit(VaultEvent.Locked)
        settle()
        assertFalse(activity.transferText().contains("notes.txt"))
        session.events.tryEmit(VaultEvent.Unlocked)
        settle()
        assertTrue(activity.transferText().contains("“notes.txt”"))
    }

    @Test
    fun `a document offers a copy out, and a folder does not`() {
        val activity = start(Opening.Open(session)).get()
        activity.hold(1)
        assertTrue(shadowOf(dialog()).items.map(CharSequence::toString).contains("Copy out of the vault"))
        dialog().dismiss()
        activity.hold(0)
        assertFalse(shadowOf(dialog()).items.map(CharSequence::toString).contains("Copy out of the vault"))
    }

    @Test
    fun `a copy out says what it costs before the picker is asked where it goes`() {
        val activity = start(Opening.Open(session)).get()
        activity.hold(1)
        dialog().choose("Copy out of the vault")
        val confirm = dialog()
        assertEquals("Copy “notes.txt” out of the vault?", shadowOf(confirm).title.toString())
        assertTrue(shadowOf(confirm).message.toString().startsWith("The copy is not encrypted."))
        assertNull("nothing is asked of the picker yet", shadowOf(activity).nextStartedActivityForResult)
        assertTrue(confirm.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        settle()
        val asked = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, asked.action)
        assertEquals("text/plain", asked.type)
        assertEquals("notes.txt", asked.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun `a document is copied out to the place chosen`() {
        val activity = start(Opening.Open(session)).get()
        activity.hold(1)
        dialog().choose("Copy out of the vault")
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        settle()
        val asked = shadowOf(activity).nextStartedActivityForResult
        val place = Uri.parse("content://elsewhere/chosen")
        shadowOf(activity).receiveResult(asked.intent, Activity.RESULT_OK, Intent().setData(place))
        settle()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), outside.written[place])
        assertEquals("Copied “notes.txt” out of the vault.", activity.transferText())
    }

    @Test
    fun `a picker given up copies nothing out`() {
        val activity = start(Opening.Open(session)).get()
        activity.hold(1)
        dialog().choose("Copy out of the vault")
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        settle()
        val asked = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(asked.intent, Activity.RESULT_CANCELED, null)
        settle()
        assertTrue(outside.written.isEmpty())
        assertEquals(View.GONE, activity.transferBar().visibility)
    }

    @Test
    fun `a copy in runs behind the service, which is handed the grant to read the files`() {
        val activity = start(Opening.Open(session)).get()
        val one = outside.add("a.txt", byteArrayOf(1))
        val two = outside.add("b.txt", byteArrayOf(2))
        activity.copyIn(one, two)
        val started = shadowOf(activity.application).nextStartedService
        assertEquals(TransferService::class.java.name, started.component!!.className)
        assertEquals(
            listOf(one, two),
            (0 until started.clipData!!.itemCount).map { started.clipData!!.getItemAt(it).uri },
        )
        assertTrue(started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, started.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test
    fun `a copy out runs behind the service, which is handed the grant to write the place chosen`() {
        val activity = start(Opening.Open(session)).get()
        activity.hold(1)
        dialog().choose("Copy out of the vault")
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        settle()
        val asked = shadowOf(activity).nextStartedActivityForResult
        val place = Uri.parse("content://elsewhere/chosen")
        shadowOf(activity).receiveResult(asked.intent, Activity.RESULT_OK, Intent().setData(place))
        settle()
        val started = shadowOf(activity.application).nextStartedService
        assertEquals(place, started.clipData!!.getItemAt(0).uri)
        assertTrue(started.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
    }

    @Test
    fun `while the service holds a copy, the bar says it carries on when the user leaves`() {
        session.writeGate = CompletableDeferred()
        val activity = start(Opening.Open(session)).get()
        activity.copyIn(outside.add("a.txt", byteArrayOf(1)))
        assertTrue(
            activity.transferText().endsWith(
                "You can leave Encri Files: the copy carries on until it is done, or until the vault locks.",
            ),
        )
        session.writeGate!!.complete(Unit)
        settle()
    }

    @Test
    @Config(sdk = [36])
    fun `from API 33 a copy asks to notify, once it has started, unless it may already`() {
        val activity = start(Opening.Open(session)).get()
        activity.copyIn(outside.add("a.txt", byteArrayOf(1)))
        assertArrayEquals(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            shadowOf(activity).lastRequestedPermission.requestedPermissions,
        )
        assertTrue("the copy did not wait for the answer", activity.transferText().startsWith("Copied 1 file"))
        shadowOf(activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val again = start(Opening.Open(session)).get()
        again.copyIn(outside.add("b.txt", byteArrayOf(2)))
        assertNull(shadowOf(again).lastRequestedPermission)
    }

    @Test
    @Config(sdk = [27])
    fun `below API 33 nothing is asked, because notifying needs no permission there`() {
        val activity = start(Opening.Open(session)).get()
        activity.copyIn(outside.add("a.txt", byteArrayOf(1)))
        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test
    fun `the screen in front is what the service asks before saying how a copy ended, and takes the saying back`() {
        val app = RuntimeEnvironment.getApplication() as TestApplication
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        SystemTransferNotice(app).answered(Report.In(1, emptyList(), 0))
        assertNotNull(shadowOf(manager).getNotification(SystemTransferNotice.ANSWER_ID))
        val controller = start(Opening.Open(session))
        assertTrue(app.transfers.isWatched)
        assertNull(shadowOf(manager).getNotification(SystemTransferNotice.ANSWER_ID))
        controller.pause().stop()
        assertFalse(app.transfers.isWatched)
    }

    @Test
    fun `the files the picker hands back are read from its clip, or from its one URI`() {
        val one = Uri.parse("content://elsewhere/1")
        val two = Uri.parse("content://elsewhere/2")
        assertEquals(listOf(one), FilesActivity.chosen(Intent().setData(one)))
        val clip = Intent().apply { clipData = ClipData.newRawUri("", one).apply { addItem(ClipData.Item(two)) } }
        assertEquals(listOf(one, two), FilesActivity.chosen(clip))
        assertTrue(FilesActivity.chosen(null).isEmpty())
    }
}
