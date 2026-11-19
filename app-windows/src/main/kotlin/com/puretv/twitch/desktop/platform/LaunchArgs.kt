package com.puretv.twitch.desktop.platform

/**
 * Command-line options. `--channel <login>` (or `--channel=<login>`) opens that channel's
 * stream once the window is up, which lets a desktop launcher, a script or a smoke test
 * start straight on a stream. Anything that is not a valid Twitch login is ignored.
 */
object LaunchArgs {
    private val LOGIN = Regex("^[A-Za-z0-9_]{1,25}$")
    const val SHOW = "show"
    private const val CHANNEL_PREFIX = "channel:"

    /**
     * What a second launch hands the running copy (see SingleInstance): the channel to open,
     * or just "show", so `--channel` works whether or not PureTV is already open.
     */
    fun request(args: Array<String>): String = channel(args)?.let { CHANNEL_PREFIX + it } ?: SHOW

    /** The channel in a request from [request], validated again since the file is outside the app. */
    fun channelFromRequest(request: String): String? =
        request.trim().removePrefix(CHANNEL_PREFIX).takeIf { request.trim().startsWith(CHANNEL_PREFIX) && LOGIN.matches(it) }

    fun channel(args: Array<String>): String? {
        val i = args.indexOfFirst { it == "--channel" || it.startsWith("--channel=") }
        if (i < 0) return null
        val value = if (args[i].startsWith("--channel=")) args[i].substringAfter('=') else args.getOrNull(i + 1)
        return value?.trim()?.lowercase()?.takeIf { LOGIN.matches(it) }
    }
}
