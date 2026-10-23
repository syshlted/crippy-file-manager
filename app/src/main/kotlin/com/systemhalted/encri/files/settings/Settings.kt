package com.systemhalted.encri.files.settings

import android.content.Context
import android.view.Window
import android.view.WindowManager

/**
 * The user's choices for this app, and only this app (AGENTS.md F3).
 *
 * Kept in the app's private preferences, which hold nothing about the vault: a choice is a yes or a no.
 * The app's data is out of Android's backups (`allowBackup` is false), so the choices stay on this phone.
 */
class Settings(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether Android may capture this app's screens: off unless the user turns it on (F3). */
    var screenshotsAllowed: Boolean
        get() = preferences.getBoolean(SCREENSHOTS, false)
        set(value) = preferences.edit().putBoolean(SCREENSHOTS, value).apply()

    /**
     * Whether this app shows previews: on unless the user turns it off (F4, encri D112). Off, it asks encri
     * for none, so no picture of a document reaches this app at all.
     */
    var previewsShown: Boolean
        get() = preferences.getBoolean(PREVIEWS, true)
        set(value) = preferences.edit().putBoolean(PREVIEWS, value).apply()

    private companion object {
        const val FILE = "settings"
        const val SCREENSHOTS = "screenshots_allowed"
        const val PREVIEWS = "previews_shown"
    }
}

/**
 * Blocks screenshots, screen recordings, casting and the app switcher's picture of [this] window unless
 * [allowed] (F3). For every window this app shows the vault in, its dialogs included: a dialog is a window
 * of its own, and the Activity's flag does not reach it.
 */
fun Window.followScreenshotSetting(allowed: Boolean) {
    if (allowed) {
        clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
