package com.puretv.twitch.desktop.ui

import com.puretv.twitch.core.playback.SleepStatus
import com.puretv.twitch.core.playback.SleepTimer
import com.puretv.twitch.core.playback.SleepTimerText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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

/**
 * One sleep timer for the whole watching session, owned by App so it survives the move
 * between the stream screen and multi-stream. [ui] is observable; the screens that show it
 * call [onActivity] for keys and mouse moves, and App calls [tick] while it runs.
 */
class SleepSession(clock: () -> Long = System::currentTimeMillis) {
    private val controller = SleepTimerController(clock)

    var ui by mutableStateOf(SleepUi())
        private set

    /**
     * Counts the times the timer ran out. [SleepUi.expired] is only true for the one tick that
     * saw it, so with several windows whichever ticked first would hide it from the rest; a
     * window instead remembers the count it has handled (see [SleepExpiryGate]).
     */
    var expiryCount by mutableIntStateOf(0)
        private set

    private fun publish(next: SleepUi) {
        if (next.expired && !ui.expired) expiryCount++
        ui = next
    }

    fun cycle() { publish(controller.cycle()) }

    fun set(minutes: Int) { publish(controller.set(minutes)) }

    fun onActivity() { controller.onActivity()?.let { publish(it) } }

    fun tick(): SleepUi = controller.tick().also { publish(it) }
}

/**
 * Lets each window act on every expiry of the sleep timer exactly once, whichever window's
 * tick noticed it. Made with the count the window starts at, so a window opened later does
 * not replay an old expiry.
 */
class SleepExpiryGate(start: Int) {
    private var handled = start

    /** True once for each new expiry seen in [expiryCount]. */
    fun take(expiryCount: Int): Boolean {
        if (expiryCount <= handled) return false
        handled = expiryCount
        return true
    }
}
