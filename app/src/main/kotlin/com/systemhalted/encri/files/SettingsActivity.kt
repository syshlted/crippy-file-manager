package com.systemhalted.encri.files

import android.app.Activity
import android.os.Bundle
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
 * and what encri answers other apps, are set in encri (encri D112).
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
        val screenshots =
            Switch(this).apply {
                setText(R.string.setting_screenshots)
                isChecked = settings.screenshotsAllowed
                setOnCheckedChangeListener { _, allowed ->
                    settings.screenshotsAllowed = allowed
                    window.followScreenshotSetting(allowed)
                }
            }
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(screenshots)
                addView(explanation(R.string.setting_screenshots_explain))
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
    }
}
