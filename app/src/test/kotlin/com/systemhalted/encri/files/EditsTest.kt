package com.systemhalted.encri.files

import android.app.AlertDialog
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.client.Right
import com.systemhalted.encri.files.link.Opening
import com.systemhalted.encri.files.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [27, 36])
class EditsTest {
    private val root = folder("root", "Vault", children = 2)
    private val photos = folder("photos", "Photos", "root")
    private val notes = document("notes", "notes.txt", "root")
    private val session = FakeSession(root, mapOf("root" to mutableListOf(notes, photos), "photos" to mutableListOf()))

    private fun dialog(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog

    private fun AlertDialog.type(text: String) = findViewById<EditText>(android.R.id.edit).setText(text)

    private fun AlertDialog.confirm() {
        getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        settle()
    }

    private fun AlertDialog.choose(label: String) {
        val items = shadowOf(this).items.map(CharSequence::toString)
        shadowOf(this).clickOnItem(items.indexOf(label))
        settle()
    }

    @Test
    fun `a new folder is made where the user is, and listed`() {
        val activity = start(Opening.Open(session)).get()
        activity.onOptionsItemSelected(RoboMenuItem(FilesActivity.MENU_NEW_FOLDER))
        dialog().type("Trips")
        dialog().confirm()
        assertEquals(listOf("create root Trips"), session.changes)
        assertEquals(listOf("Photos", "Trips", "notes.txt"), activity.rowNames())
    }

    @Test
    fun `a blank name is refused here, and nothing is asked of encri`() {
        val activity = start(Opening.Open(session)).get()
        activity.onOptionsItemSelected(RoboMenuItem(FilesActivity.MENU_NEW_FOLDER))
        val asked = dialog()
        asked.type("   ")
        asked.confirm()
        assertTrue(session.changes.isEmpty())
        assertNotEquals(asked, dialog())
    }

    @Test
    fun `a document is renamed from its long press, its extension left out of the selection`() {
        val activity = start(Opening.Open(session)).get()
        activity.hold(1)
        dialog().choose("Rename")
        val field = dialog().findViewById<EditText>(android.R.id.edit)
        assertEquals("notes.txt", field.text.toString())
        assertTrue("the name is ready to type over", field.hasFocus())
        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
            dialog().window!!.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE,
        )
        assertEquals(0, field.selectionStart)
        assertEquals("notes".length, field.selectionEnd)
        dialog().type("journal.txt")
        dialog().confirm()
        assertEquals(listOf("rename notes journal.txt"), session.changes)
        assertEquals(listOf("Photos", "journal.txt"), activity.rowNames())
    }

    @Test
    fun `a rename to the same name asks nothing`() {
        val activity = start(Opening.Open(session)).get()
        activity.hold(1)
        dialog().choose("Rename")
        dialog().confirm()
        assertTrue(session.changes.isEmpty())
    }

    @Test
    fun `a delete is confirmed first, and a folder's says everything in it goes too`() {
        val activity = start(Opening.Open(session)).get()
        activity.hold(0)
        dialog().choose("Delete")
        val asked = shadowOf(dialog()).message.toString()
        assertTrue(asked.startsWith("Everything in the folder is deleted with it."))
        assertEquals("Delete “Photos”?", shadowOf(dialog()).title.toString())
        assertTrue(session.changes.isEmpty())
        dialog().confirm()
        assertEquals(listOf("delete photos"), session.changes)
        assertEquals(listOf("notes.txt"), activity.rowNames())
    }

    @Test
    fun `a move is made by going to the folder and moving it there`() {
        val activity = start(Opening.Open(session)).get()
        activity.hold(1)
        dialog().choose("Move")
        assertEquals(View.VISIBLE, activity.movingBar().visibility)
        assertFalse("already in this folder", activity.moveHereButton().isEnabled)
        activity.tap(0)
        assertTrue(activity.moveHereButton().isEnabled)
        activity.moveHereButton().performClick()
        settle()
        assertEquals(listOf("move notes photos"), session.changes)
        assertEquals(View.GONE, activity.movingBar().visibility)
        assertEquals(listOf("notes.txt"), activity.rowNames())
    }

    @Test
    fun `a folder cannot be moved into itself`() {
        val activity = start(Opening.Open(session)).get()
        activity.hold(0)
        dialog().choose("Move")
        activity.tap(0)
        assertFalse(activity.moveHereButton().isEnabled)
    }

    @Test
    fun `a move can be given up`() {
        val activity = start(Opening.Open(session)).get()
        activity.hold(1)
        dialog().choose("Move")
        activity.cancelMoveButton().performClick()
        settle()
        assertEquals(View.GONE, activity.movingBar().visibility)
        assertTrue(session.changes.isEmpty())
    }

    @Test
    fun `a change encri refuses is said in the user's terms, and the move goes on until it works`() {
        session.failChanges = Failure.NAME_TAKEN
        val activity = start(Opening.Open(session)).get()
        activity.hold(1)
        dialog().choose("Move")
        activity.tap(0)
        activity.moveHereButton().performClick()
        settle()
        assertEquals("It was not moved", shadowOf(dialog()).title.toString())
        assertEquals("Something with that name is already there.", shadowOf(dialog()).message.toString())
        assertEquals(View.VISIBLE, activity.movingBar().visibility)
    }

    @Test
    fun `a session allowed to read only offers no change, and offers to ask for more`() {
        val readOnly = FakeSession(root, session.tree, setOf(Right.READ))
        val activity = start(Opening.Open(readOnly)).get()
        assertFalse(activity.menu().findItem(FilesActivity.MENU_NEW_FOLDER).isVisible)
        assertTrue(activity.menu().findItem(FilesActivity.MENU_MORE_RIGHTS).isVisible)
        activity.hold(0)
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("encri has not let this app change anything"))
        // A copy out needs only reading (F6).
        activity.hold(1)
        assertEquals(listOf("Copy out of the vault"), shadowOf(dialog()).items.map(CharSequence::toString))
        dialog().dismiss()
        activity.onOptionsItemSelected(RoboMenuItem(FilesActivity.MENU_MORE_RIGHTS))
        val asked = shadowOf(activity).nextStartedActivityForResult
        assertEquals("com.systemhalted.encri.api.action.AUTHORIZE", asked.intent.action)
    }

    @Test
    fun `a session allowed everything offers every change and does not ask for more`() {
        val activity = start(Opening.Open(session)).get()
        assertTrue(activity.menu().findItem(FilesActivity.MENU_NEW_FOLDER).isVisible)
        assertFalse(activity.menu().findItem(FilesActivity.MENU_MORE_RIGHTS).isVisible)
        activity.hold(1)
        assertEquals(
            listOf("Copy out of the vault", "Rename", "Move", "Delete"),
            shadowOf(dialog()).items.map(CharSequence::toString),
        )
    }

    @Test
    fun `a dialog naming a document follows the screenshot setting`() {
        val activity = start(Opening.Open(session)).get()
        activity.hold(1)
        assertNotEquals(0, dialog().window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        Settings(RuntimeEnvironment.getApplication()).screenshotsAllowed = true
        activity.hold(1)
        assertEquals(0, dialog().window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }
}
