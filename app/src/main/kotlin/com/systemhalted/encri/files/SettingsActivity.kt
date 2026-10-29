package com.systemhalted.encri.files

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.systemhalted.encri.files.settings.Settings
import com.systemhalted.encri.files.settings.followScreenshotSetting
import com.systemhalted.encri.files.ui.padForSystemBars

/**
 * This app's settings (AGENTS.md F3). Each says that it changes Encri Files alone: encri's own screens,
 * and what encri answers other apps, are set in encri (encri D112). Below them, the third-party notices
 * (F10), which belong to no state of the vault and so to no screen of the browser.
 *
 * Not exported: nothing outside this app has a reason to start it.
 */
class SettingsActivity : Activity() {
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        window.followScreenshotSetting(settings.screenshotsAllowed)
        setTitle(R.string.settings)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        window.followScreenshotSetting(settings.screenshotsAllowed)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != android.R.id.home) return super.onOptionsItemSelected(item)
        finish()
        return true
    }

    private fun build(): ScrollView {
        val pad = (PADDING_DP * resources.displayMetrics.density).toInt()
        val previews =
            Switch(this).apply {
                setText(R.string.setting_previews)
                isChecked = settings.previewsShown
                setOnCheckedChangeListener { _, shown -> settings.previewsShown = shown }
            }
        val screenshots =
            Switch(this).apply {
                setText(R.string.setting_screenshots)
                isChecked = settings.screenshotsAllowed
                setOnCheckedChangeListener { _, allowed ->
                    settings.screenshotsAllowed = allowed
                    window.followScreenshotSetting(allowed)
                }
            }
        // A row rather than a switch: it opens the notices (F10). In the switches' own size and colour, and as tall
        // as a touch target, because a plain TextView is drawn in the explanations' grey and reads as one of them.
        val notices =
            TextView(this).apply {
                setText(R.string.notices)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, previews.textSize)
                setTextColor(previews.textColors)
                minHeight = (TOUCH_TARGET_DP * resources.displayMetrics.density).toInt()
                gravity = Gravity.CENTER_VERTICAL
                val pressed = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, pressed, true)
                setBackgroundResource(pressed.resourceId)
                setOnClickListener { startActivity(Intent(this@SettingsActivity, NoticesActivity::class.java)) }
            }
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(previews)
                addView(explanation(R.string.setting_previews_explain))
                addView(screenshots)
                addView(explanation(R.string.setting_screenshots_explain))
                addView(notices)
                addView(explanation(R.string.notices_explain))
            }
        return ScrollView(this).apply {
            setPadding(pad, pad, pad, pad)
            clipToPadding = false
            padForSystemBars(pad)
            addView(column)
        }
    }

    private fun explanation(text: Int) =
        TextView(this).apply {
            setText(text)
            setPadding(0, 0, 0, (PADDING_DP * resources.displayMetrics.density).toInt())
        }

    private companion object {
        const val PADDING_DP = 16

        /** The platform's minimum touch target. */
        const val TOUCH_TARGET_DP = 48
    }
}
