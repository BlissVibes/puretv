package com.puretv.twitch.desktop.platform

import java.awt.Desktop
import java.awt.desktop.AppReopenedListener
import javax.swing.SwingUtilities

/**
 * macOS application events. Cmd+Q and the Dock's Quit would otherwise end the JVM
 * directly; they go through [onQuit] instead, the same quit the tray uses, so players are
 * released and the stores flushed. A click on the Dock icon while PureTV has no window
 * showing (it was started at login into the menu bar) calls [onReopen]. No-op elsewhere.
 */
object MacAppEvents {
    fun install(onQuit: () -> Unit, onReopen: () -> Unit) {
        if (HostOs.current != HostOs.MACOS || !Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        runCatching {
            if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                // The response is deliberately left unanswered: cancelQuit() would tell macOS
                // PureTV refused (aborting a logout or restart), and performQuit() would end the
                // JVM before the stores are flushed. onQuit ends the process itself, which is
                // what macOS waits for.
                desktop.setQuitHandler { _, _ -> SwingUtilities.invokeLater(onQuit) }
            }
        }
        runCatching {
            if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
                desktop.addAppEventListener(AppReopenedListener { onReopen() })
            }
        }
    }
}
