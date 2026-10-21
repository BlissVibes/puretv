package com.puretv.twitch.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Decides whether the pointer counts as "the viewer moved the mouse". */
object PointerActivity {
    /** How often the real pointer position is read while it matters. */
    const val POLL_MS = 100L

    /**
     * True when the pointer is somewhere new and inside [bounds] (the window, in screen
     * coordinates). The first reading, with nothing to compare to, is not a move, and a pointer
     * that stays put never counts, so a layout change cannot make the controls flicker.
     */
    fun moved(last: Point?, now: Point?, bounds: Rectangle?): Boolean {
        if (last == null || now == null) return false
        if (last == now) return false
        return bounds == null || bounds.contains(now)
    }
}

/**
 * Reports pointer movement over the window by reading the screen position of the pointer, for the
 * player screens that hide their controls.
 *
 * The video is drawn in a native window, and mouse events over it never reach Compose, so a
 * fullscreen player (where the video covers everything) could not see the mouse move and the
 * controls never came back. The screen position does not care which window is underneath.
 * Polling is only on while [enabled], when the controls can actually be hidden.
 */
@Composable
fun NativePointerActivity(enabled: Boolean, onMoved: () -> Unit) {
    val window = LocalAwtWindow.current
    val latestOnMoved by rememberUpdatedState(onMoved)
    LaunchedEffect(enabled, window) {
        if (!enabled) return@LaunchedEffect
        var last: Point? = null
        while (isActive) {
            val now = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
            val bounds = window?.takeIf { it.isShowing }?.bounds
            if (PointerActivity.moved(last, now, bounds)) latestOnMoved()
            last = now
            delay(PointerActivity.POLL_MS)
        }
    }
}
