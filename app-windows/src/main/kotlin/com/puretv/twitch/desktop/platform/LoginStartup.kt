package com.puretv.twitch.desktop.platform

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

/**
 * "Start PureTV when I sign in". PureTV starts with [BACKGROUND_ARG] and stays in the
 * tray, so signing in does not throw a window in the viewer's face. Each OS has its own
 * per-user mechanism, none of which needs admin rights:
 *
 * - Windows: a value under the current user's Run key. The uninstaller deletes it (puretv.iss).
 * - Linux: a freedesktop autostart entry, `~/.config/autostart/puretv-for-twitch.desktop`.
 * - macOS: a LaunchAgent, `~/Library/LaunchAgents/com.puretv.twitch.plist`.
 *
 * Only the installed app can register itself: a dev `gradle run` has no launcher to
 * point the OS at, so [isAvailable] is false there and Settings says so.
 */
object LoginStartup {
    const val BACKGROUND_ARG = "--background"
    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE_NAME = "PureTV"
    internal const val MAC_LABEL = "com.puretv.twitch"

    /** The installed launcher, set by jpackage; null in a dev run. */
    private val launcher: String?
        get() = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }

    val isAvailable: Boolean get() = launcher != null

    /** What Settings calls the switch on this OS. */
    fun label(os: HostOs = HostOs.current): String = when (os) {
        HostOs.WINDOWS -> "Start with Windows"
        HostOs.MACOS -> "Open at login"
        HostOs.LINUX -> "Start when I log in"
    }

    /** Adds or removes the entry. False when it could not be changed. */
    fun setEnabled(enabled: Boolean): Boolean = runCatching {
        val home = File(System.getProperty("user.home"))
        when (HostOs.current) {
            HostOs.WINDOWS -> setWindows(enabled)
            HostOs.LINUX -> setFile(linuxEntry(home, System.getenv("XDG_CONFIG_HOME")), enabled) { linuxDesktopEntry(it) }
            HostOs.MACOS -> setFile(macAgent(home), enabled) { macLaunchAgent(it) }
        }
    }.getOrDefault(false)

    private fun setWindows(enabled: Boolean): Boolean {
        if (enabled) {
            val exe = launcher ?: return false
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, "\"$exe\" $BACKGROUND_ARG")
        } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
        }
        return true
    }

    private fun setFile(file: File, enabled: Boolean, content: (String) -> String): Boolean {
        if (!enabled) {
            if (file.exists()) file.delete()
            return true
        }
        val exe = launcher ?: return false
        file.parentFile?.mkdirs()
        file.writeText(content(exe))
        return true
    }

    internal fun linuxEntry(home: File, xdgConfigHome: String?): File =
        File(xdgConfigHome?.takeIf { it.startsWith("/") }?.let(::File) ?: File(home, ".config"), "autostart/puretv-for-twitch.desktop")

    internal fun macAgent(home: File): File = File(home, "Library/LaunchAgents/$MAC_LABEL.plist")

    /** A freedesktop entry. The launcher path has spaces, so it is quoted as the spec asks. */
    internal fun linuxDesktopEntry(exe: String): String {
        val escaped = exe.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$")
        return "[Desktop Entry]\n" +
            "Type=Application\n" +
            "Name=PureTV for Twitch\n" +
            "Exec=\"$escaped\" $BACKGROUND_ARG\n" +
            "Terminal=false\n" +
            "X-GNOME-Autostart-enabled=true\n"
    }

    internal fun macLaunchAgent(exe: String): String {
        val path = exe.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            |<plist version="1.0">
            |<dict>
            |    <key>Label</key>
            |    <string>$MAC_LABEL</string>
            |    <key>ProgramArguments</key>
            |    <array>
            |        <string>$path</string>
            |        <string>$BACKGROUND_ARG</string>
            |    </array>
            |    <key>RunAtLoad</key>
            |    <true/>
            |</dict>
            |</plist>
            |""".trimMargin()
    }
}
