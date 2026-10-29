package com.puretv.twitch.desktop.platform

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * "Start PureTV with Windows": a value under the current user's Run key, so it
 * needs no admin rights and goes away with the user. PureTV starts with
 * [BACKGROUND_ARG] and stays in the tray, so signing in to Windows does not throw
 * a window in the viewer's face. The uninstaller deletes the value (puretv.iss).
 *
 * Only the installed app can register itself: a dev `gradle run` has no launcher
 * exe to point Windows at, so [isAvailable] is false there and Settings says so.
 */
object WindowsStartup {
    const val BACKGROUND_ARG = "--background"
    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE_NAME = "PureTV"

    /** The installed launcher exe, set by jpackage; null in a dev run. */
    private val launcherExe: String?
        get() = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }

    /** Windows only: the Run key has no macOS equivalent here (a LaunchAgent would be the port). */
    val isAvailable: Boolean get() = Platform.isWindows && launcherExe != null

    /** Adds or removes the Run value. False when it could not be changed. */
    fun setEnabled(enabled: Boolean): Boolean = runCatching {
        if (!Platform.isWindows) return false
        if (enabled) {
            val exe = launcherExe ?: return false
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, "\"$exe\" $BACKGROUND_ARG")
        } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
        }
        true
    }.getOrDefault(false)
}
