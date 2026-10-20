package com.puretv.twitch.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepTimerControllerTest {
    private var now = 0L
    private val controller = SleepTimerController { now }
    private val minute = 60_000L

    @Test fun startsOff() {
        val ui = controller.tick()
        assertFalse(ui.running)
        assertEquals("Sleep: Off", ui.label)
        assertNull(ui.warning)
    }

    @Test fun cycleStartsTheFirstPresetThenWalksOnAndBackToOff() {
        assertEquals(15, controller.cycle().minutes)
        assertEquals(30, controller.cycle().minutes)
        repeat(3) { controller.cycle() } // 60, 90, 120
        assertEquals(0, controller.cycle().minutes)
    }

    @Test fun showsTheMinutesLeft() {
        controller.set(30)
        now = 6 * minute
        assertEquals("Sleep: 24 min left", controller.tick().label)
        assertTrue(controller.tick().running)
    }

    @Test fun warnsInTheLastMinuteWithKeyWording() {
        controller.set(15)
        now = 14 * minute + 18_000
        val ui = controller.tick()
        assertEquals("Stopping in 42 seconds. Press any key to keep watching", ui.warning)
    }

    @Test fun activityInTheWarningMinuteExtendsAndClearsTheWarning() {
        controller.set(15)
        now = 14 * minute + 30_000
        val ui = controller.onActivity()
        assertEquals(null, ui?.warning)
        assertTrue(ui!!.running)
        assertNull(controller.onActivity())
    }

    @Test fun expiresOnce() {
        controller.set(15)
        now = 15 * minute
        assertTrue(controller.tick().expired)
        assertFalse(controller.tick().expired)
    }

    @Test fun aPresetFromTheMenuCanTurnItOff() {
        controller.set(60)
        assertFalse(controller.set(0).running)
    }
}
