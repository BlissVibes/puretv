package com.puretv.twitch.desktop.platform

import java.awt.Desktop
import java.net.URI

/**
 * Opens [url] in the user's default system browser. Returns false if no browser
 * could be launched (headless / unsupported environment). Never throws -- a failed
 * launch must not crash the caller.
 *
 * Java's own launcher needs GNOME/GTK integration on Linux and is missing under many
 * other desktops, so when it is unavailable or fails the OS's opener runs instead:
 * `xdg-open` on Linux, `open` on macOS.
 */
fun openInBrowser(url: String): Boolean {
    val viaDesktop = runCatching {
        Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) &&
            run { Desktop.getDesktop().browse(URI(url)); true }
    }.getOrDefault(false)
    if (viaDesktop) return true
    val command = browserCommand(url, HostOs.current) ?: return false
    return runCatching { ProcessBuilder(command).redirectErrorStream(true).start(); true }.getOrDefault(false)
}

/**
 * The OS opener for [url], or null. Only plain https URLs are handed to it, so nothing
 * that looks like an option or a local file can reach the command line.
 */
internal fun browserCommand(url: String, os: HostOs): List<String>? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
    return when (os) {
        HostOs.LINUX -> listOf("xdg-open", uri.toString())
        HostOs.MACOS -> listOf("open", uri.toString())
        HostOs.WINDOWS -> null
    }
}
