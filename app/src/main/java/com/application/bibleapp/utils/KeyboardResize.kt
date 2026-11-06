package com.application.bibleapp.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/**
 * The window makes room for the keyboard while the calling screen is open, so its fields stay
 * above it (pair with [imePadding] on the screen's content). Without this the window pans up
 * instead, which in landscape pushed the top bar off screen and the form under the status bar
 * (#225). The rest of the app keeps its default, unchanged; restored on leaving.
 */
@Composable
fun ResizeForKeyboard() {
    val window = LocalContext.current.findActivity()?.window ?: return
    DisposableEffect(window) {
        KeyboardResizeRequests.add(window)
        onDispose { KeyboardResizeRequests.remove(window) }
    }
}

/**
 * Counts the screens asking for resize, per window, so one screen's cleanup never undoes
 * another's request. Main thread only, like composition.
 */
private object KeyboardResizeRequests {
    private class Request(val previousMode: Int, var count: Int)

    private val requests = WeakHashMap<Window, Request>()

    @Suppress("DEPRECATION") // still how a window opts into resizing for the keyboard
    fun add(window: Window) {
        val request = requests.getOrPut(window) { Request(restorableMode(window.attributes.softInputMode), 0) }
        request.count++
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // After a rotation the screen asks before the new window is attached, and attaching
        // dropped the request, so the window panned again in landscape (#225). Asking again once
        // attached (a posted runnable waits for that) makes it stick.
        window.decorView.post {
            if (requests.containsKey(window)) {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
    }

    /**
     * The manifest sets no mode, so the window starts "unspecified", which Android turns into
     * pan for a Compose screen. Handing "unspecified" back keeps the last mode (resize), so the
     * rest of the app stayed resized after leaving; pan is put back explicitly instead.
     */
    private fun restorableMode(mode: Int): Int {
        val adjust = WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        return if (mode and adjust == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED) {
            @Suppress("DEPRECATION")
            mode or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        } else {
            mode
        }
    }

    fun remove(window: Window) {
        val request = requests[window] ?: return
        if (--request.count == 0) {
            requests.remove(window)
            window.setSoftInputMode(request.previousMode)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
