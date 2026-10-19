package com.puretv.twitch.core.stream

/**
 * How close to live the "Low latency" setting plays. With it off every player
 * keeps its own default: VLC starts 15 s back from the end of the playlist, and
 * ExoPlayer aims three target durations back (18 s on Twitch's playlists, plus
 * however old the newest segment is). With it on, ad-free playlists also
 * refresh every segment, and a player on one rendition gets Twitch's prefetch
 * hints as segments (see `adblock.TwitchPrefetch`).
 */
object LiveLatency {
    /**
     * ExoPlayer (phone and TV): how far behind live to aim, by Twitch's
     * program date-time. ExoPlayer nudges the playback speed to stay on it, and
     * after a rebuffer moves the target back, never past [EXO_MAX_OFFSET_MS].
     * The newest listed segment is already 2 to 4 s old and ExoPlayer wants 3 s
     * buffered to resume after a stall, which is why this is not lower.
     */
    const val EXO_TARGET_OFFSET_MS = 8_000L
    const val EXO_MIN_OFFSET_MS = 6_000L
    const val EXO_MAX_OFFSET_MS = 15_000L
    const val EXO_MIN_SPEED = 0.97f
    const val EXO_MAX_SPEED = 1.03f

    /**
     * VLC (Windows): how far back from the end of the playlist to start, as the
     * `adaptive-livedelay` media option. Three of Twitch's 2 s segments, so a
     * late segment still has two more buffered behind it.
     */
    const val VLC_LIVE_DELAY_MS = 6_000L
}
