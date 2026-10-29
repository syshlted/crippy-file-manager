package com.systemhalted.encri.files

import android.app.Activity
import android.os.Bundle
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import com.systemhalted.encri.files.settings.Settings
import com.systemhalted.encri.files.settings.followScreenshotSetting
import com.systemhalted.encri.files.ui.NOTICES_ASSET
import com.systemhalted.encri.files.ui.noticeText
import com.systemhalted.encri.files.ui.padForSystemBars

/**
 * The third-party notices this app's libraries require in a binary distribution (AGENTS.md F10).
 *
 * The Apache License asks for its text and any NOTICE file to go with the app, kotlinx.coroutines has one,
 * and the Kotlin standard library carries ThreeTen-Backport's code, whose licence asks for its notice to be
 * reproduced. The APK carries none of them: none of those libraries' jars holds a licence file.
 *
 * What it shows is an asset, written by hand and held to the build by `:app:checkThirdPartyNotices`: every
 * module a shipped APK carries is named in it, at the version that ships. The screen only draws it.
 *
 * Opened from [SettingsActivity], which the menu offers in every state, because a licence's terms do not
 * depend on there being a vault. It follows the screenshot setting as every window here does (F3). Not
 * exported. Read on the main thread: the asset is 14 KB inside the APK.
 */
class NoticesActivity : Activity() {
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        window.followScreenshotSetting(settings.screenshotsAllowed)
        setTitle(R.string.notices)
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
        val notices =
            TextView(this).apply {
                text = noticeText(assets.open(NOTICES_ASSET).bufferedReader().use { it.readText() })
                // A licence is something a reader may want to quote.
                setTextIsSelectable(true)
            }
        return ScrollView(this).apply {
            setPadding(pad, pad, pad, pad)
            clipToPadding = false
            padForSystemBars(pad)
            addView(notices)
        }
    }

    private companion object {
        const val PADDING_DP = 16
    }
}
