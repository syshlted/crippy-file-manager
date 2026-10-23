package com.systemhalted.encri.files

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.view.WindowManager
import com.systemhalted.encri.client.EncriResult
import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.files.link.Opening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast

/** Sharing a document out of the vault through the share sheet (AGENTS.md F7). */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [27, 36])
class ShareScreenTest {
    private val root = folder("root", "Vault", children = 2)
    private val photos = folder("photos", "Photos", "root")
    private val beach = document("beach", "beach.jpg", "root", "image/jpeg")
    private val session = FakeSession(root, mapOf("root" to listOf(beach, photos), "photos" to emptyList()))

    private fun dialog(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog

    /** Long-presses the document, chooses Share, and answers the confirmation with [confirm]. */
    private fun share(confirm: Boolean = true): FilesActivity {
        val activity = start(Opening.Open(session)).get()
        activity.hold(1)
        val items = shadowOf(dialog()).items.map(CharSequence::toString)
        shadowOf(dialog()).clickOnItem(items.indexOf("Share"))
        settle()
        val button = if (confirm) AlertDialog.BUTTON_POSITIVE else AlertDialog.BUTTON_NEGATIVE
        dialog().getButton(button).performClick()
        settle()
        return activity
    }

    @Test
    fun `a document offers a share first, and a folder offers none`() {
        val activity = start(Opening.Open(session)).get()
        activity.hold(1)
        assertEquals("Share", shadowOf(dialog()).items.first().toString())
        dialog().dismiss()
        activity.hold(0)
        assertFalse(shadowOf(dialog()).items.map(CharSequence::toString).contains("Share"))
    }

    @Test
    fun `a share says what it costs, and nothing is handed over before the user agrees`() {
        val activity = start(Opening.Open(session)).get()
        activity.hold(1)
        val items = shadowOf(dialog()).items.map(CharSequence::toString)
        shadowOf(dialog()).clickOnItem(items.indexOf("Share"))
        settle()
        val confirm = dialog()
        assertEquals("Share “beach.jpg”?", shadowOf(confirm).title.toString())
        val said = shadowOf(confirm).message.toString()
        assertTrue(said.contains("not encrypted"))
        assertTrue(said.contains("after the vault locks or Encri Files is closed"))
        assertTrue(confirm.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertTrue(session.handedOff.isEmpty())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `a share given up hands nothing over`() {
        val activity = share(confirm = false)
        assertTrue(session.handedOff.isEmpty())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `the share sheet is given a handoff, readable by the app the user picks`() {
        val activity = share()
        assertEquals(listOf("beach"), session.handedOff)
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val sent = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        val handoff = Uri.parse("content://com.systemhalted.encri.open/beach")
        assertEquals(Intent.ACTION_SEND, sent.action)
        assertEquals("image/jpeg", sent.type)
        @Suppress("DEPRECATION")
        assertEquals(handoff, sent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(handoff, sent.clipData!!.getItemAt(0).uri)
        assertEquals(
            "",
            sent.clipData!!
                .description.label
                .toString(),
        )
        assertNotEquals(0, sent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertNotEquals(
            "the share sheet carries the grant on",
            0,
            chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    @Test
    @Suppress("DEPRECATION") // The shadow's simplest way to make an activity answer a share.
    fun `encri is left out of the share sheet, and nothing else is`() {
        val sends = Intent(Intent.ACTION_SEND).setType("image/jpeg")
        val packages = shadowOf(RuntimeEnvironment.getApplication().packageManager)
        packages.addResolveInfoForIntent(sends, target("com.systemhalted.encri", "com.systemhalted.encri.ShareToEncri"))
        packages.addResolveInfoForIntent(sends, target("org.example.mail", "org.example.mail.Compose"))
        val chooser = shadowOf(share()).nextStartedActivity

        @Suppress("DEPRECATION")
        val excluded = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)!!.toList()
        assertEquals(listOf(ComponentName("com.systemhalted.encri", "com.systemhalted.encri.ShareToEncri")), excluded)
    }

    @Test
    fun `with no encri to take a share, nothing is left out`() {
        val chooser = shadowOf(share()).nextStartedActivity
        assertFalse(chooser.hasExtra(Intent.EXTRA_EXCLUDE_COMPONENTS))
    }

    private fun target(
        packageName: String,
        name: String,
    ) = ResolveInfo().apply {
        activityInfo =
            ActivityInfo().apply {
                this.packageName = packageName
                this.name = name
            }
    }

    @Test
    fun `a handoff encri refuses says so and opens no share sheet`() {
        session.handOffAnswer = EncriResult.Failed(Failure.LOCKED, "locked")
        val activity = share()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("encri would not hand this document to another app.", ShadowToast.getTextOfLatestToast())
    }
}
