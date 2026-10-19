package com.puretv.twitch.core.adblock

/**
 * Twitch's low-latency hint. A live media playlist (requested with `fast_bread`)
 * ends with `#EXT-X-TWITCH-PREFETCH:<url>` lines: the next one or two segments,
 * still being written but already fetchable before Twitch lists them. Players
 * ignore the tag, so they sit a few seconds further from live than Twitch's own
 * player. Handing those over as ordinary segments (what streamlink's low-latency
 * mode does) closes the gap. When Twitch lists the segment, the next refresh has
 * the very same URL in the same position (checked on a live stream, 2026-10-19),
 * so the player's view of the timeline stays consistent.
 *
 * Never during an ad: a prefetched segment can be an ad segment the ad markers
 * have not caught up with yet, which is why vaft drops these lines whenever it
 * strips ads ([strip]).
 */
object TwitchPrefetch {
    private const val TAG = "#EXT-X-TWITCH-PREFETCH:"

    /** The segment length Twitch uses, for a playlist that lists none to copy. */
    private const val DEFAULT_DURATION = "2.000"

    /**
     * Turns each prefetch line into a segment, as long as the last listed one
     * (Twitch's segments are all the same length). A URL that is already listed
     * is dropped rather than repeated.
     */
    fun promote(playlist: String): String {
        if (TAG !in playlist) return playlist
        val lines = playlist.lines()
        val listed = lines.mapNotNullTo(HashSet()) { line ->
            line.trim().takeIf { it.isNotEmpty() && !it.startsWith("#") }
        }
        val duration = lines.lastOrNull { it.startsWith("#EXTINF:") }
            ?.substringAfter(':')?.substringBefore(',')?.trim()
            ?.takeIf { it.toDoubleOrNull() != null }
            ?: DEFAULT_DURATION
        return lines.flatMap { line ->
            if (!line.startsWith(TAG)) return@flatMap listOf(line)
            val uri = line.removePrefix(TAG).trim()
            if (uri.isEmpty() || uri in listed) emptyList() else listOf("#EXTINF:$duration,live", uri)
        }.joinToString("\n")
    }

    /** Removes the prefetch lines, leaving only what Twitch has listed. */
    fun strip(playlist: String): String =
        if (TAG !in playlist) playlist else playlist.lines().filterNot { it.startsWith(TAG) }.joinToString("\n")
}
