package com.puretv.twitch.core.vod

/**
 * D-pad scrubbing for the TV VOD player. Left/Right and FF/RW move a PENDING
 * position, which the screen shows on its bar; the real seek happens once,
 * [COMMIT_DELAY_MS] after the last key. Holding a key therefore races the
 * pending position ahead without asking the player for dozens of seeks, each
 * of which would throw its buffer away.
 *
 * A held key arrives as repeats, and the step grows with the repeat count so a
 * two-hour broadcast can be crossed in seconds: 10 s per press, 30 s after a
 * short hold, 60 s after a long one. FF/RW (absent on many remotes) jump 60 s.
 */
object VodScrub {
    const val STEP_MS = 10_000L
    const val FAST_STEP_MS = 30_000L
    const val FASTEST_STEP_MS = 60_000L
    const val JUMP_MS = 60_000L

    /** How long after the last scrub key the seek fires. */
    const val COMMIT_DELAY_MS = 600L

    private const val FAST_AFTER_REPEATS = 5
    private const val FASTEST_AFTER_REPEATS = 15

    /** The step for one Left/Right key event, given how many times it has auto-repeated. */
    fun stepFor(repeatCount: Int): Long = when {
        repeatCount > FASTEST_AFTER_REPEATS -> FASTEST_STEP_MS
        repeatCount > FAST_AFTER_REPEATS -> FAST_STEP_MS
        else -> STEP_MS
    }

    /**
     * The new pending position: [deltaMs] from the current pending one, or from
     * [positionMs] when no scrub is pending, kept inside the video. With an
     * unknown length (still loading) it only keeps the result above zero.
     */
    fun move(pendingMs: Long?, positionMs: Long, durationMs: Long, deltaMs: Long): Long {
        val next = (pendingMs ?: positionMs) + deltaMs
        return if (durationMs > 0) next.coerceIn(0L, durationMs) else next.coerceAtLeast(0L)
    }
}
