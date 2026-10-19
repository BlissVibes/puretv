package com.puretv.twitch.core.stream

/**
 * How close to live the "Low latency" setting plays. With it off every player
 * keeps its own default: VLC starts 15 s back from the end of the playlist, and
 * ExoPlayer aims three target durations back (18 s on Twitch's playlists). With
 * it on, Twitch's prefetch hints also become segments (see
 * `adblock.TwitchPrefetch`), which moves the end of the playlist itself closer
 * to live.
 */
object LiveLatency {
    /**
     * ExoPlayer (phone and TV): how far behind live to aim, by Twitch's
     * program date-time. ExoPlayer nudges the playback speed to stay on it, and
     * after a rebuffer moves the target back, never past [EXO_MAX_OFFSET_MS].
     */
    const val EXO_TARGET_OFFSET_MS = 5_000L
    const val EXO_MIN_OFFSET_MS = 4_000L
    const val EXO_MAX_OFFSET_MS = 12_000L
    const val EXO_MIN_SPEED = 0.97f
    const val EXO_MAX_SPEED = 1.03f

    /**
     * VLC (Windows): how far back from the end of the playlist to start, as the
     * `adaptive-livedelay` media option. Three of Twitch's 2 s segments, so a
     * late segment still has two more buffered behind it.
     */
    const val VLC_LIVE_DELAY_MS = 6_000L
}
