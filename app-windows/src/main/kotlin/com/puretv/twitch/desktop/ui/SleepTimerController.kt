package com.puretv.twitch.desktop.ui

import com.puretv.twitch.core.playback.SleepStatus
import com.puretv.twitch.core.playback.SleepTimer
import com.puretv.twitch.core.playback.SleepTimerText

/** What the player screen shows for the sleep timer. */
data class SleepUi(
    /** The preset in force, 0 for Off. */
    val minutes: Int = 0,
    /** For example "Sleep: 24 min left", or "Sleep: Off". */
    val label: String = "Sleep: Off",
    /** The last-minute banner text, or null when it is not showing. */
    val warning: String? = null,
    /** The time ran out. */
    val expired: Boolean = false,
) {
    val running: Boolean get() = minutes > 0 && !expired
}

/**
 * The Windows face of core's [SleepTimer]. Nothing here runs on its own: the screen calls
 * [tick] about once a second while [SleepUi.running] and shows what comes back, so the
 * logic tests with a fake clock. Mouse moves and key presses call [onActivity], which in
 * the last minute adds 15 minutes.
 */
class SleepTimerController(clock: () -> Long) {
    private val timer = SleepTimer(clock)

    /** The next preset (Off, 15, 30, 60, 90, 120 minutes), counting from now. */
    fun cycle(): SleepUi {
        timer.cycle()
        return tick()
    }

    /** A specific preset from the menu; 0 turns the timer off. */
    fun set(minutes: Int): SleepUi {
        timer.set(minutes)
        return tick()
    }

    /** Any key or mouse move. Returns the new state when it bought more time, otherwise null. */
    fun onActivity(): SleepUi? = if (timer.onActivity()) tick() else null

    fun tick(): SleepUi = when (val status = timer.status()) {
        SleepStatus.Off -> SleepUi()
        is SleepStatus.Running -> SleepUi(
            minutes = timer.minutes,
            label = SleepTimerText.remaining(status.remainingMs),
            warning = if (status.warning) SleepTimerText.warning(status.remainingMs, "key") else null,
        )
        SleepStatus.Expired -> SleepUi(expired = true)
    }
}
