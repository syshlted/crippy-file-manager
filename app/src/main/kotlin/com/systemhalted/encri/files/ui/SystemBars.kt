package com.systemhalted.encri.files.ui

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Pads a screen's content out from under the system bars.
 *
 * This app targets SDK 36, so from Android 15 its window is drawn edge to edge whatever it asks for, and
 * content that never reads the insets is drawn under the status bar and the title bar. encri found that
 * on a phone rather than in a test (its D39), which is why every screen here calls this. The title bar
 * needs no term of its own: the insets dispatched to the content already include it.
 */
fun View.padForSystemBars(pad: Int) {
    setOnApplyWindowInsetsListener { view, insets ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
        } else {
            padForLegacyInsets(view, insets, pad)
        }
        insets
    }
}

/** `WindowInsets.getInsets(Type)` is API 30 and minSdk is 27, so below it these are the only accessors. */
@Suppress("DEPRECATION")
private fun padForLegacyInsets(
    view: View,
    insets: WindowInsets,
    pad: Int,
) {
    view.setPadding(
        pad + insets.systemWindowInsetLeft,
        pad + insets.systemWindowInsetTop,
        pad + insets.systemWindowInsetRight,
        pad + insets.systemWindowInsetBottom,
    )
}
