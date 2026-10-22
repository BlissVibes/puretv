package com.puretv.twitch.desktop.platform

/**
 * Command-line options. `--channel <login>` (or `--channel=<login>`) opens that channel's
 * stream once the window is up, which lets a desktop launcher, a script or a smoke test
 * start straight on a stream. Anything that is not a valid Twitch login is ignored.
 */
object LaunchArgs {
    private val LOGIN = Regex("^[A-Za-z0-9_]{1,25}$")

    fun channel(args: Array<String>): String? {
        val i = args.indexOfFirst { it == "--channel" || it.startsWith("--channel=") }
        if (i < 0) return null
        val value = if (args[i].startsWith("--channel=")) args[i].substringAfter('=') else args.getOrNull(i + 1)
        return value?.trim()?.lowercase()?.takeIf { LOGIN.matches(it) }
    }
}
