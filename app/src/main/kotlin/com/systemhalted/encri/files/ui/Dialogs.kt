package com.systemhalted.encri.files.ui

import android.app.AlertDialog
import android.view.WindowManager
import com.systemhalted.encri.files.settings.Settings
import com.systemhalted.encri.files.settings.followScreenshotSetting

/**
 * Shows this builder's dialog under the screenshot setting (F3), with the keyboard up when it asks for [typing]. A
 * dialog is a window of its own, so the Activity's flag does not reach it, and every dialog here shows a name.
 */
fun AlertDialog.Builder.showFollowing(
    settings: Settings,
    typing: Boolean = false,
) {
    val dialog = create()
    dialog.window?.followScreenshotSetting(settings.screenshotsAllowed)
    if (typing) dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    dialog.show()
}
