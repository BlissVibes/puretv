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
