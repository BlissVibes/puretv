package com.puretv.twitch.core.adblock

/**
 * Low-latency playlist edits for ad-free live playlists.
 *
 * Twitch's low-latency hint: a live media playlist (requested with `fast_bread`)
 * ends with `#EXT-X-TWITCH-PREFETCH:<url>` lines, the next one or two segments,
 * still being written but already fetchable before Twitch lists them. Players
 * ignore the tag. Handing those over as ordinary segments ([promote], what
 * streamlink's low-latency mode does) moves the end of the playlist a few
 * seconds closer to live. When Twitch lists the segment, the next refresh has
 * the very same URL in the same position (checked on a live stream, 2026-10-19),
 * so the player's view of the timeline stays consistent.
 *
 * Only for a player that plays ONE rendition. A segment that is still being
 * written downloads at about its own bitrate (measured 2026-10-19: 860 KB of a
 * 3.4 Mbps rendition took 1.5 to 2 s, against about 0.1 s once listed), and a
 * player choosing renditions by download speed would read that as a slow link
 * and keep stepping the quality down.
 *
 * Never during an ad: a prefetched segment can be an ad segment the ad markers
 * have not caught up with yet, which is why vaft drops these lines whenever it
 * strips ads ([strip]).
 */
object TwitchPrefetch {
    private const val TAG = "#EXT-X-TWITCH-PREFETCH:"
    private const val TARGET_DURATION = "#EXT-X-TARGETDURATION:"

    /** The segment length Twitch uses, for a playlist that lists none to copy. */
    private const val DEFAULT_DURATION = "2.000"

    /**
     * An ad-free playlist at the live edge: [tightenTargetDuration] always, and
     * [promote] when [promotePrefetch] (a player playing one rendition).
     */
    fun liveEdge(playlist: String, promotePrefetch: Boolean): String {
        val tightened = tightenTargetDuration(playlist)
        return if (promotePrefetch) promote(tightened) else strip(tightened)
    }

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

    /**
     * Twitch declares a 6 s target duration for its 2 s segments, and ExoPlayer
     * waits a whole target duration after each change before reloading a live
     * playlist (media3 1.4.1), so the newest segment it knows of can be 4 to 8 s
     * old. Lowered to the longest listed segment (rounded, as the HLS spec rounds
     * EXTINF against it), the playlist refreshes every segment. Never raised.
     */
    fun tightenTargetDuration(playlist: String): String {
        val lines = playlist.lines()
        val index = lines.indexOfFirst { it.startsWith(TARGET_DURATION) }
        if (index < 0) return playlist
        val declared = lines[index].removePrefix(TARGET_DURATION).trim().toLongOrNull() ?: return playlist
        val longest = lines.filter { it.startsWith("#EXTINF:") }
            .mapNotNull { it.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() }
            .maxOrNull() ?: return playlist
        // Half up: a 2.5 s segment needs 3 (kotlin.math.round would give 2).
        val tight = kotlin.math.floor(longest + 0.5).toLong().coerceAtLeast(1)
        if (tight >= declared) return playlist
        return lines.toMutableList().also { it[index] = "$TARGET_DURATION$tight" }.joinToString("\n")
    }
}
