package com.puretv.twitch.desktop.player

/** The pure part of starting a past video in libVLC (see [VlcPlayer.playVod]). */
object VodPlayback {
    private const val END_SLACK_MS = 2_000L

    /** The libVLC per-media options that start a video at [startMs]; none at the beginning. */
    fun startOptions(startMs: Long): Array<String> {
        if (startMs <= 0) return emptyArray()
        return arrayOf(":start-time=${startMs / 1000}.${(startMs % 1000).toString().padStart(3, '0')}")
    }

    /** The place to start when the video is played again from [positionMs]: its start if it had ended, else where it was. */
    fun replayStartMs(positionMs: Long, durationMs: Long): Long =
        if (durationMs > 0 && positionMs >= durationMs - END_SLACK_MS) 0 else positionMs.coerceAtLeast(0)
}
