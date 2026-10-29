package com.systemhalted.encri.files

import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Switch
import android.widget.TextView
import com.systemhalted.encri.files.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboMenuItem

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 36])
class SettingsActivityTest {
    private val settings = Settings(RuntimeEnvironment.getApplication())

    private fun SettingsActivity.row(index: Int): TextView =
        ((findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup)
            .getChildAt(index) as TextView

    private fun SettingsActivity.switch(index: Int): Switch = row(index) as Switch

    private fun SettingsActivity.previews(): Switch = switch(0)

    private fun SettingsActivity.screenshots(): Switch = switch(2)

    private fun SettingsActivity.secure() = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    fun `screenshots are blocked until the user allows them`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertFalse(activity.screenshots().isChecked)
        assertTrue(activity.secure())
        assertFalse(settings.screenshotsAllowed)
    }

    @Test
    fun `allowing screenshots is kept, and applies to this screen at once`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        activity.screenshots().isChecked = true
        assertTrue(settings.screenshotsAllowed)
        assertFalse(activity.secure())
        activity.screenshots().isChecked = false
        assertFalse(settings.screenshotsAllowed)
        assertNotEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test
    fun `the switch shows what was chosen before`() {
        settings.screenshotsAllowed = true
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertTrue(activity.screenshots().isChecked)
        assertFalse(activity.secure())
    }

    @Test
    fun `previews are shown until the user turns them off, and the choice is kept`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertTrue(activity.previews().isChecked)
        activity.previews().isChecked = false
        assertFalse(settings.previewsShown)
        val again = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertFalse(again.previews().isChecked)
    }

    @Test
    fun `the notices are a row below the settings, and open their own screen`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val notices = activity.row(4)
        assertEquals("Third-party notices", notices.text.toString())
        // Drawn as the switches are, not in the explanations' grey, so it reads as something to press.
        assertEquals(activity.previews().currentTextColor, notices.currentTextColor)
        assertNotEquals(activity.row(1).currentTextColor, notices.currentTextColor)

        assertTrue(notices.performClick())

        assertEquals(
            NoticesActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className,
        )
    }

    @Test
    fun `up leaves the settings`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        assertTrue(activity.onOptionsItemSelected(RoboMenuItem(android.R.id.home)))
        assertTrue(activity.isFinishing)
        assertEquals("Settings", activity.title.toString())
    }
}
