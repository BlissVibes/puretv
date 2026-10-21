package com.puretv.twitch.desktop.player

/** The pure part of starting and reloading a past video in libVLC (see [VlcPlayer.playVod]). */
object VodPlayback {
    /** A place this close to the end counts as the end. */
    const val END_SLACK_MS = 2_000L

    /** The libVLC per-media options that start a video at [startMs]; none at the beginning. */
    fun startOptions(startMs: Long): Array<String> {
        if (startMs <= 0) return emptyArray()
        return arrayOf(":start-time=${startMs / 1000}.${(startMs % 1000).toString().padStart(3, '0')}")
    }

    /**
     * The last place the video is known to have been at. Only a real reading while playing
     * moves it: the 0 that libVLC reports while it opens new media must never reset it.
     */
    fun nextGood(lastGoodMs: Long, newTimeMs: Long, playing: Boolean): Long =
        if (playing && newTimeMs > 0) newTimeMs else lastGoodMs

    /** False for a clock reading that would send a video that already has a place back to 0. */
    fun shouldPublishTime(lastGoodMs: Long, newTimeMs: Long): Boolean = newTimeMs > 0 || lastGoodMs <= 0

    /** True when [positionMs] is at the end of a video of [durationMs] (within [END_SLACK_MS]). */
    fun isEnded(positionMs: Long, durationMs: Long): Boolean = durationMs > 0 && positionMs >= durationMs - END_SLACK_MS

    /** The place to start when the video is played again from [positionMs]: its start if it had ended, else where it was. */
    fun replayStartMs(positionMs: Long, durationMs: Long): Long =
        if (isEnded(positionMs, durationMs)) 0 else positionMs.coerceAtLeast(0)
}
