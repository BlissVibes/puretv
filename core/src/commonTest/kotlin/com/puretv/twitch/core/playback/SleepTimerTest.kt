package com.puretv.twitch.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SleepTimerTest {
    private var now = 0L
    private val timer = SleepTimer { now }
    private val minute = 60_000L

    @Test fun startsOff() {
        assertEquals(SleepStatus.Off, timer.status())
        assertEquals(0, timer.minutes)
    }

    @Test fun cyclesThroughThePresetsAndBackToOff() {
        val seen = List(7) { timer.cycle() }
        assertEquals(listOf(15, 30, 60, 90, 120, 0, 15), seen)
        timer.cycle() // 30
        timer.set(0)
        assertEquals(SleepStatus.Off, timer.status())
    }

    @Test fun countsDownThenExpiresOnce() {
        timer.set(15)
        now = 10 * minute
        assertEquals(SleepStatus.Running(5 * minute, warning = false), timer.status())
        now = 15 * minute
        assertEquals(SleepStatus.Expired, timer.status())
        assertEquals(SleepStatus.Off, timer.status())
        assertEquals(0, timer.minutes)
    }

    @Test fun theWarningStartsInTheLastMinute() {
        timer.set(15)
        now = 14 * minute - 1
        assertEquals(false, (timer.status() as SleepStatus.Running).warning)
        now = 14 * minute
        assertEquals(SleepStatus.Running(minute, warning = true), timer.status())
    }

    @Test fun activityBeforeTheWarningChangesNothing() {
        timer.set(15)
        now = 5 * minute
        assertFalse(timer.onActivity())
        assertEquals(SleepStatus.Running(10 * minute, warning = false), timer.status())
    }

    @Test fun activityInTheWarningMinuteAddsFifteenMinutes() {
        timer.set(15)
        now = 14 * minute + 30_000
        assertTrue(timer.onActivity())
        assertEquals(SleepStatus.Running(15 * minute + 30_000, warning = false), timer.status())
        // The warning is over, so more activity does not stack extra time.
        assertFalse(timer.onActivity())
    }

    @Test fun activityWhenOffOrExpiredDoesNothing() {
        assertFalse(timer.onActivity())
        timer.set(15)
        now = 16 * minute
        assertFalse(timer.onActivity())
        assertEquals(SleepStatus.Expired, timer.status())
    }

    @Test fun changingThePresetRestartsTheCountdown() {
        timer.set(15)
        now = 10 * minute
        timer.set(30)
        assertEquals(SleepStatus.Running(30 * minute, warning = false), timer.status())
    }

    @Test fun wordsAreFriendly() {
        assertEquals("Off", SleepTimerText.preset(0))
        assertEquals("15 min", SleepTimerText.preset(15))
        assertEquals("1 hour", SleepTimerText.preset(60))
        assertEquals("1 h 30 min", SleepTimerText.preset(90))
        assertEquals("2 hours", SleepTimerText.preset(120))
        assertEquals(
            "Stopping in 60 seconds. Press any button to keep watching",
            SleepTimerText.warning(60_000, "button"),
        )
        assertEquals("Stopping in 1 second. Press any key to keep watching", SleepTimerText.warning(400, "key"))
        assertEquals("Sleep: 24 min left", SleepTimerText.remaining(23 * minute + 1))
        assertEquals("Sleep: 1 min left", SleepTimerText.remaining(5_000))
    }
}
