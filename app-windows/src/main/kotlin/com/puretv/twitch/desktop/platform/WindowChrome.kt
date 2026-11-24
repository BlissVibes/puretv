package com.puretv.twitch.desktop.platform

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Window
import javax.swing.RootPaneContainer

/**
 * How PureTV's windows are framed on each OS.
 *
 * - Windows: a borderless frame with PureTV's own title bar and buttons; [WindowsNative]
 *   gives it back Aero Snap and edge resizing. Fullscreen stretches that frame over the
 *   monitor (exclusive fullscreen fights the video surface).
 * - macOS: the system frame, its title bar made transparent so PureTV's bar fills the top
 *   with the traffic lights on it, and the system's own fullscreen (its own Space).
 * - Linux: the window manager's frame, which is what gives a window its resize edges,
 *   tiling and keyboard moves under every desktop; PureTV's bar stays as a header without
 *   window buttons. Fullscreen is the window manager's.
 */
object WindowChrome {
    /** True where PureTV draws the title bar and the window buttons itself. */
    fun customFrame(os: HostOs = HostOs.current): Boolean = os == HostOs.WINDOWS

    /** True where fullscreen should be the system's fullscreen rather than a stretched frame. */
    fun nativeFullscreen(os: HostOs = HostOs.current): Boolean = os != HostOs.WINDOWS

    /** True where PureTV's own header moves the window (no system title bar to grab). */
    fun headerMovesWindow(os: HostOs = HostOs.current): Boolean = os != HostOs.LINUX

    /** Room to leave at the left of the header for the macOS traffic lights. */
    fun leadingInset(os: HostOs = HostOs.current): Dp = if (os == HostOs.MACOS) 76.dp else 0.dp

    /**
     * True where minimizing the main window hides PureTV in the tray. On macOS a minimized
     * window belongs in the Dock, and the menu bar icon is for alerts.
     */
    fun minimizeToTray(os: HostOs = HostOs.current): Boolean = os != HostOs.MACOS

    /** Makes a system-framed macOS window look like the others: content up to the top edge. */
    fun styleNativeFrame(window: Window, os: HostOs = HostOs.current) {
        if (os != HostOs.MACOS) return
        val root = (window as? RootPaneContainer)?.rootPane ?: return
        root.putClientProperty("apple.awt.fullWindowContent", true)
        root.putClientProperty("apple.awt.transparentTitleBar", true)
        root.putClientProperty("apple.awt.windowTitleVisible", false)
    }
}

/**
 * Notices the OS ending a system fullscreen PureTV asked for: the macOS green button or
 * Ctrl+Cmd+F, a window manager's shortcut. Without it PureTV would keep its fullscreen
 * layout in a window that is no longer fullscreen.
 *
 * Feed it every placement while the window is (as far as PureTV knows) fullscreen. The
 * placement can still read as normal for a moment after PureTV asks for fullscreen, so
 * only a fullscreen that was actually reached and then left counts.
 */
class NativeFullscreenExit {
    private var reached = false

    /** True when [placement] means the OS just left the fullscreen PureTV is in. */
    fun left(inFullscreen: Boolean, placement: androidx.compose.ui.window.WindowPlacement): Boolean {
        if (!inFullscreen) {
            reached = false
            return false
        }
        if (placement == androidx.compose.ui.window.WindowPlacement.Fullscreen) {
            reached = true
            return false
        }
        if (!reached) return false
        reached = false
        return true
    }
}

/**
 * Keeps the macOS fullscreen Space in step with what PureTV asked for. macOS ignores a
 * fullscreen toggle while the Space animation from the previous one is still running (about
 * a second, longer with a stream playing), so leaving fullscreen soon after entering it left
 * the window fullscreen with the normal layout inside. The controller asks this every tick
 * while a request is pending and acts on the answer.
 */
object NativePlacementSync {

    enum class Step {
        /** Ask the window for the target placement now. */
        ISSUE,
        /** A transition may still be running: check again on the next tick. */
        WAIT,
        /** The window is where PureTV wants it and has had time to settle. */
        DONE,
        /** Asked [MAX_ATTEMPTS] times and the window never followed: stop trying. */
        GIVE_UP,
    }

    /** Longer than macOS takes to enter or leave its fullscreen Space (measured ~1.35 s with a stream). */
    const val SETTLE_MS = 1600L
    const val TICK_MS = 200L
    const val MAX_ATTEMPTS = 4

    /**
     * [sinceIssueMs] is the time since the window was last asked for a placement, or null if
     * it has not been asked yet. Never asks again within [SETTLE_MS] of the last ask, so a
     * request can never land on top of a running animation.
     */
    fun step(
        target: androidx.compose.ui.window.WindowPlacement,
        actual: androidx.compose.ui.window.WindowPlacement,
        sinceIssueMs: Long?,
        attempts: Int,
    ): Step = when {
        sinceIssueMs != null && sinceIssueMs < SETTLE_MS -> Step.WAIT
        actual == target -> Step.DONE
        attempts >= MAX_ATTEMPTS -> Step.GIVE_UP
        else -> Step.ISSUE
    }
}
