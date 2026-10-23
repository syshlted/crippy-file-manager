package com.systemhalted.encri.files

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import com.systemhalted.encri.client.EncriResult
import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.client.VaultEvent
import com.systemhalted.encri.files.browse.Browser
import com.systemhalted.encri.files.link.Opener
import com.systemhalted.encri.files.link.Opening
import com.systemhalted.encri.files.link.VaultLink
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboMenu
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowToast

/** The application under test: the real link and browser over a stand-in for encri. */
class TestApplication :
    Application(),
    Vaults {
    override lateinit var link: VaultLink
    override lateinit var browser: Browser

    fun use(opener: Opener) {
        val scope = MainScope()
        link = VaultLink(opener, scope)
        browser = Browser(link, scope)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [27, 36])
class FilesActivityTest {
    private val root = folder("root", "Vault", children = 2)
    private val photos = folder("photos", "Photos", "root", children = 1)
    private val notes = document("notes", "notes.txt", "root", sizeBytes = 2048)
    private val beach = document("beach", "beach.jpg", "photos", "image/jpeg")
    private val session = FakeSession(root, mapOf("root" to listOf(notes, photos), "photos" to listOf(beach)))

    private fun start(vararg answers: Opening): ActivityController<FilesActivity> {
        (RuntimeEnvironment.getApplication() as TestApplication).use(FakeOpener(*answers))
        val controller = Robolectric.buildActivity(FilesActivity::class.java).setup()
        settle()
        return controller
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun Activity.body(): ViewGroup = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup

    private fun Activity.message(): TextView = body().getChildAt(0) as TextView

    private fun Activity.remedy(): Button = body().getChildAt(1) as Button

    private fun Activity.list(): ListView = body().getChildAt(2) as ListView

    private fun Activity.rowNames(): List<String> =
        (0 until list().adapter.count).map { position ->
            val row = list().adapter.getView(position, null, list())
            row.findViewById<TextView>(android.R.id.text1).text.toString()
        }

    private fun Activity.tap(position: Int) {
        list().performItemClick(list().adapter.getView(position, null, list()), position, position.toLong())
        settle()
    }

    @Test
    fun `the vault is listed where the user was let in, folders first`() {
        val activity = start(Opening.Open(session)).get()
        assertEquals(listOf("Photos", "notes.txt"), activity.rowNames())
        assertEquals(View.GONE, activity.message().visibility)
        assertEquals(View.GONE, activity.remedy().visibility)
        assertEquals("Encri Files", activity.title.toString())
    }

    @Test
    fun `nothing on the screen reaches a screenshot or the recents thumbnail`() {
        val activity = start(Opening.Open(session)).get()
        assertNotEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test
    fun `a folder is gone into, named in the title, and left by up`() {
        val activity = start(Opening.Open(session)).get()
        activity.tap(0)
        assertEquals(listOf("beach.jpg"), activity.rowNames())
        assertEquals("Photos", activity.title.toString())
        activity.onOptionsItemSelected(RoboMenuItem(android.R.id.home))
        settle()
        assertEquals(listOf("Photos", "notes.txt"), activity.rowNames())
    }

    @Test
    @Config(sdk = [27])
    @Suppress("DEPRECATION")
    fun `back goes up a folder, and at the top it leaves`() {
        val activity = start(Opening.Open(session)).get()
        activity.tap(0)
        activity.onBackPressed()
        settle()
        assertEquals(listOf("Photos", "notes.txt"), activity.rowNames())
        assertFalse(activity.isFinishing)
        activity.onBackPressed()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `a document is handed to whichever app opens its type, and may be read by it`() {
        val activity = start(Opening.Open(session)).get()
        activity.tap(1)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("content://com.systemhalted.encri.open/notes", started.data.toString())
        assertEquals("text/plain", started.type)
        assertNotEquals(0, started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(listOf("notes"), session.handedOff)
    }

    @Test
    fun `a document no app opens says so`() {
        val activity = start(Opening.Open(session)).get()
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        activity.tap(1)
        assertEquals("No app on this phone opens text/plain.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a handoff encri refuses says so and opens nothing`() {
        session.handOffAnswer = EncriResult.Failed(Failure.LOCKED, "locked")
        val activity = start(Opening.Open(session)).get()
        activity.tap(1)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("encri would not hand this document to another app.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `an app not let in asks encri, for a result, for reading alone`() {
        val activity = start(Opening.NotAuthorized).get()
        assertEquals(View.VISIBLE, activity.remedy().visibility)
        assertEquals("Ask encri", activity.remedy().text.toString())
        assertTrue(activity.rowNames().isEmpty())
        activity.remedy().performClick()
        val asked = shadowOf(activity).nextStartedActivityForResult
        assertEquals("com.systemhalted.encri.api.action.AUTHORIZE", asked.intent.action)
        assertEquals("com.systemhalted.encri", asked.intent.`package`)
    }

    @Test
    fun `coming back from encri's screen asks for a session again`() {
        val activity = start(Opening.NotAuthorized, Opening.Open(session)).get()
        activity.remedy().performClick()
        val asked = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(asked.intent, Activity.RESULT_OK, null)
        settle()
        assertEquals(listOf("Photos", "notes.txt"), activity.rowNames())
    }

    @Test
    fun `a refusal on encri's screen is said`() {
        val activity = start(Opening.NotAuthorized).get()
        activity.remedy().performClick()
        val asked = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(asked.intent, Activity.RESULT_CANCELED, null)
        settle()
        assertEquals("Encri Files was not let in.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a locked vault is unlocked on encri's own screen`() {
        val activity = start(Opening.Locked).get()
        assertEquals("The vault is locked.", activity.message().text.toString())
        activity.remedy().performClick()
        val asked = shadowOf(activity).nextStartedActivityForResult
        assertEquals("com.systemhalted.encri.action.UNLOCK", asked.intent.action)
    }

    @Test
    fun `encri out of reach can be tried again`() {
        val activity = start(Opening.Unavailable, Opening.Open(session)).get()
        assertEquals("Try again", activity.remedy().text.toString())
        activity.remedy().performClick()
        settle()
        assertEquals(listOf("Photos", "notes.txt"), activity.rowNames())
    }

    @Test
    fun `a refusal nothing here can fix offers no button`() {
        val activity = start(Opening.NotTrusted("ab12")).get()
        assertEquals(View.GONE, activity.remedy().visibility)
        assertTrue(
            activity
                .message()
                .text
                .toString()
                .endsWith("is ab12."),
        )
    }

    @Test
    fun `locking from the menu locks the vault, and the screen then shows nothing of it`() {
        val activity = start(Opening.Open(session)).get()
        activity.onOptionsItemSelected(RoboMenuItem(FilesActivity.MENU_LOCK))
        settle()
        assertEquals(1, session.locks)
        runBlocking { session.events.emit(VaultEvent.Locked) }
        settle()
        assertTrue(activity.rowNames().isEmpty())
        assertEquals("The vault is locked.", activity.message().text.toString())
        assertEquals("Encri Files", activity.title.toString())
    }

    @Test
    fun `coming back to the screen lists the folder again`() {
        val controller = start(Opening.Open(session))
        controller
            .pause()
            .stop()
            .restart()
            .start()
            .resume()
        settle()
        assertEquals(listOf("root", "root"), session.listed)
    }

    @Test
    fun `refreshing lists the folder again`() {
        val activity = start(Opening.Open(session)).get()
        activity.onOptionsItemSelected(RoboMenuItem(FilesActivity.MENU_RELOAD))
        settle()
        assertEquals(listOf("root", "root"), session.listed)
    }

    private fun Activity.menu(): RoboMenu =
        RoboMenu(this).also {
            onCreateOptionsMenu(it)
            onPrepareOptionsMenu(it)
        }

    @Test
    fun `an open vault's menu offers a lock and a refresh`() {
        val menu = start(Opening.Open(session)).get().menu()
        assertTrue(menu.findItem(FilesActivity.MENU_LOCK).isVisible)
        assertTrue(menu.findItem(FilesActivity.MENU_RELOAD).isVisible)
    }

    @Test
    fun `with no session the menu offers neither`() {
        val menu = start(Opening.NotAuthorized).get().menu()
        assertFalse(menu.findItem(FilesActivity.MENU_LOCK).isVisible)
        assertFalse(menu.findItem(FilesActivity.MENU_RELOAD).isVisible)
    }
}
