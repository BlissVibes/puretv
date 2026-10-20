package com.puretv.twitch.core.playback

/** Where the sleep timer stands right now. */
sealed interface SleepStatus {
    /** No timer is running. */
    data object Off : SleepStatus

    /** Counting down. [warning] is true in the last minute, when the banner shows. */
    data class Running(val remainingMs: Long, val warning: Boolean) : SleepStatus

    /** The time is up. Reported once, so playback stops once. */
    data object Expired : SleepStatus
}

/**
 * A sleep timer for lean-back viewing: pick Off, 15, 30, 60, 90 or 120 minutes and
 * playback stops when it runs out. In the last [WARNING_MS] any key press or mouse
 * move (the screen calls [onActivity]) adds [EXTENSION_MS], so someone still awake keeps
 * watching without opening a menu. It is not saved: a restart starts with it off.
 *
 * The clock is passed in so tests run without waiting. The screen polls [status] about
 * once a second; nothing here runs on its own.
 */
class SleepTimer(private val clock: () -> Long) {
    private var deadlineAtMs: Long? = null

    /** The preset minutes last chosen with [set] or [cycle]; 0 is Off. */
    var minutes: Int = 0
        private set

    /** Starts the timer for [minutes], or turns it off for 0 (or less). */
    fun set(minutes: Int) {
        this.minutes = minutes.coerceAtLeast(0)
        deadlineAtMs = if (this.minutes == 0) null else clock() + this.minutes * MINUTE_MS
    }

    /** Moves to the next preset (Off wraps to 15) and starts it. Returns the new minutes. */
    fun cycle(): Int {
        val next = PRESETS_MINUTES[(PRESETS_MINUTES.indexOf(minutes) + 1) % PRESETS_MINUTES.size]
        set(next)
        return next
    }

    fun cancel() = set(0)

    /** The state now. Once it returns [SleepStatus.Expired] the timer is off again. */
    fun status(): SleepStatus {
        val deadline = deadlineAtMs ?: return SleepStatus.Off
        val remaining = deadline - clock()
        if (remaining <= 0) {
            deadlineAtMs = null
            minutes = 0
            return SleepStatus.Expired
        }
        return SleepStatus.Running(remaining, warning = remaining <= WARNING_MS)
    }

    /**
     * The viewer pressed a key or moved the mouse. In the warning minute that adds
     * [EXTENSION_MS] and returns true; at any other time it does nothing.
     */
    fun onActivity(): Boolean {
        val deadline = deadlineAtMs ?: return false
        val remaining = deadline - clock()
        if (remaining <= 0 || remaining > WARNING_MS) return false
        deadlineAtMs = deadline + EXTENSION_MS
        return true
    }

    companion object {
        /** Off, then the presets; 0 means Off. */
        val PRESETS_MINUTES = listOf(0, 15, 30, 60, 90, 120)
        const val WARNING_MS = 60_000L
        const val EXTENSION_MS = 15 * 60_000L
        private const val MINUTE_MS = 60_000L
    }
}

/** The words the sleep timer shows. */
object SleepTimerText {
    /** "Off", "30 min", "1 hour", "1 h 30 min" or "2 hours". */
    fun preset(minutes: Int): String = when {
        minutes <= 0 -> "Off"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> if (minutes == 60) "1 hour" else "${minutes / 60} hours"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    /** The warning banner. Seconds round up so it never says 0 while still playing; [input] is "button" or "key". */
    fun warning(remainingMs: Long, input: String): String {
        val seconds = ((remainingMs + 999) / 1000).coerceIn(1, 60)
        val unit = if (seconds == 1L) "second" else "seconds"
        return "Stopping in $seconds $unit. Press any $input to keep watching"
    }

    /** What the sleep control says while running, for example "Sleep: 24 min left". */
    fun remaining(remainingMs: Long): String {
        val minutes = ((remainingMs + 59_999) / 60_000).coerceAtLeast(1)
        return "Sleep: $minutes min left"
    }
}
