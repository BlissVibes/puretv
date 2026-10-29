package com.puretv.twitch.desktop.platform

import java.io.File

/**
 * Which desktop OS this build is running on. The desktop module started life as
 * Windows-only; the macOS port keeps the same code and branches here where the
 * two differ (data folder, native window tricks, how VLC is bundled and how its
 * video reaches the screen).
 */
object Platform {
    private val osName: String = System.getProperty("os.name", "").lowercase()

    val isWindows: Boolean = osName.startsWith("windows")
    val isMac: Boolean = osName.startsWith("mac")

    /** "Apple Silicon" or "Intel" style arch label, for diagnostics. */
    val arch: String = System.getProperty("os.arch", "")
}

/**
 * Where PureTV keeps its files on this OS.
 *
 *  - Windows: `%APPDATA%\PureTwitch` (roaming) and `%LOCALAPPDATA%\PureTwitch` (caches),
 *    exactly as before the port, so an existing install keeps its sign-in and settings.
 *  - macOS:   `~/Library/Application Support/PureTV` for everything, and
 *             `~/Library/Caches/PureTV` for caches.
 *  - Other:   `~/.puretv` (dev fallback).
 */
object AppPaths {
    val dataDir: File by lazy {
        when {
            Platform.isWindows -> File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PureTwitch")
            Platform.isMac -> File(System.getProperty("user.home"), "Library/Application Support/PureTV")
            else -> File(System.getProperty("user.home"), ".puretv")
        }
    }

    /** Large, regenerable data (image and emote caches). */
    val cacheDir: File by lazy {
        when {
            Platform.isWindows -> File(
                System.getenv("LOCALAPPDATA") ?: System.getenv("APPDATA") ?: System.getProperty("user.home"),
                "PureTwitch",
            )
            Platform.isMac -> File(System.getProperty("user.home"), "Library/Caches/PureTV")
            else -> File(dataDir, "cache")
        }
    }
}
