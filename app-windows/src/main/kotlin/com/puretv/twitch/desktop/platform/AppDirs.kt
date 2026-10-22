package com.puretv.twitch.desktop.platform

import java.io.File

/** The desktop OS PureTV is running on. */
enum class HostOs {
    WINDOWS, MACOS, LINUX;

    companion object {
        val current: HostOs = from(System.getProperty("os.name", ""))

        fun from(osName: String): HostOs {
            val n = osName.lowercase()
            return when {
                n.startsWith("windows") -> WINDOWS
                n.startsWith("mac") || n.startsWith("darwin") -> MACOS
                // Linux and the BSDs share the freedesktop conventions this app relies on.
                else -> LINUX
            }
        }
    }
}

/**
 * Where PureTV keeps its files, following each OS's convention:
 *
 * - Windows: `%APPDATA%\PureTwitch` for settings, the sign-in and logs, and
 *   `%LOCALAPPDATA%\PureTwitch\cache` for caches. These are the paths every
 *   release so far has used; moving them would sign every Windows user out.
 * - macOS: `~/Library/Application Support/PureTwitch` and `~/Library/Caches/PureTwitch`.
 * - Linux: `$XDG_CONFIG_HOME/PureTwitch` (default `~/.config`) and
 *   `$XDG_CACHE_HOME/PureTwitch` (default `~/.cache`).
 */
object AppDirs {
    private const val NAME = "PureTwitch"

    /** Settings, the encrypted sign-in, history and logs. */
    val data: File by lazy { dataDir(HostOs.current, System::getenv, System.getProperty("user.home")) }

    /** Files that can be deleted at any time and are fetched again. */
    val cache: File by lazy { cacheDir(HostOs.current, System::getenv, System.getProperty("user.home")) }

    /** The image disk cache. On Windows it has always lived beside the settings. */
    val imageCache: File by lazy {
        if (HostOs.current == HostOs.WINDOWS) File(data, "image_cache") else File(cache, "images")
    }

    internal fun dataDir(os: HostOs, env: (String) -> String?, home: String): File = when (os) {
        HostOs.WINDOWS -> File(env("APPDATA") ?: home, NAME)
        HostOs.MACOS -> File(home, "Library/Application Support/$NAME")
        HostOs.LINUX -> File(xdg(env("XDG_CONFIG_HOME")) ?: File(home, ".config"), NAME)
    }

    internal fun cacheDir(os: HostOs, env: (String) -> String?, home: String): File = when (os) {
        HostOs.WINDOWS -> File(env("LOCALAPPDATA") ?: env("APPDATA") ?: home, "$NAME/cache")
        HostOs.MACOS -> File(home, "Library/Caches/$NAME")
        HostOs.LINUX -> File(xdg(env("XDG_CACHE_HOME")) ?: File(home, ".cache"), NAME)
    }

    // The XDG spec says a relative path in these variables is invalid and must be ignored.
    // (Checked as a leading "/" rather than File.isAbsolute, which is only meaningful on the OS running it.)
    private fun xdg(value: String?): File? = value?.takeIf { it.startsWith("/") }?.let(::File)
}
