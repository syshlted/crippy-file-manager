package com.systemhalted.encri.files

import android.content.ComponentName
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import com.systemhalted.encri.files.settings.Settings
import com.systemhalted.encri.files.ui.NOTICES_ASSET
import com.systemhalted.encri.files.ui.noticeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboMenuItem

/**
 * The notices screen (F10): it shows the whole asset, as [noticeText] draws it, in a window that follows the
 * screenshot setting and that no other app can open.
 *
 * Whether the asset names everything the APK carries is `:app:checkThirdPartyNotices`'s to say, from the
 * resolved build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 36])
class NoticesActivityTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun NoticesActivity.notices(): TextView {
        val content = findViewById<ViewGroup>(android.R.id.content)
        return (content.getChildAt(0) as ScrollView).getChildAt(0) as TextView
    }

    private fun NoticesActivity.secure() = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    fun `the screen shows the whole asset, to the last line of the Apache License`() {
        val activity = Robolectric.buildActivity(NoticesActivity::class.java).setup().get()
        val asset =
            context.assets
                .open(NOTICES_ASSET)
                .bufferedReader()
                .use { it.readText() }

        val shown = activity.notices().text.toString()

        assertEquals(noticeText(asset).toString(), shown)
        assertTrue("the notices stop before the licence they all point to", "END OF TERMS AND CONDITIONS" in shown)
        assertTrue(
            "kotlinx.coroutines' NOTICE file is not shown",
            "Copyright 2016-2025 JetBrains s.r.o and contributors" in shown,
        )
        assertFalse("a heading's mark reached the screen", shown.lines().any { it.startsWith("# ") })
        assertEquals("Third-party notices", activity.title.toString())
    }

    @Test
    fun `its window blocks screenshots until the user allows them, as every window here does`() {
        val blocked = Robolectric.buildActivity(NoticesActivity::class.java).setup().get()
        assertTrue(blocked.secure())

        Settings(context).screenshotsAllowed = true
        val allowed = Robolectric.buildActivity(NoticesActivity::class.java).setup().get()
        assertFalse(allowed.secure())
    }

    @Test
    fun `no other app can open it`() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, NoticesActivity::class.java), 0)

        assertFalse(info.exported)
    }

    @Test
    fun `up leaves the notices`() {
        val activity = Robolectric.buildActivity(NoticesActivity::class.java).setup().get()

        assertTrue(activity.onOptionsItemSelected(RoboMenuItem(android.R.id.home)))
        assertTrue(activity.isFinishing)
    }
}
