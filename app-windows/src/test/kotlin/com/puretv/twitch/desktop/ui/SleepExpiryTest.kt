package com.puretv.twitch.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowBoundsGateTest {
    @Test fun minimizedAndMaximizedWindowsAreNotRemembered() {
        assertTrue(WindowBoundsGate.shouldSave(extendedState = 0, x = -1500, y = 100))
        assertFalse(WindowBoundsGate.shouldSave(extendedState = java.awt.Frame.ICONIFIED, x = 10, y = 10))
        assertFalse(WindowBoundsGate.shouldSave(extendedState = java.awt.Frame.MAXIMIZED_BOTH, x = 0, y = 0))
        // Windows parks a minimized window at -32000.
        assertFalse(WindowBoundsGate.shouldSave(extendedState = 0, x = -32000, y = -32000))
    }
}

class SleepExpiryTest {
    private var now = 0L
    private val session = SleepSession { now }
    private val minute = 60_000L

    @Test fun everyWindowSeesAnExpiryEvenWhenOneTickHidIt() {
        val windowA = SleepExpiryGate(session.expiryCount)
        val windowB = SleepExpiryGate(session.expiryCount)
        session.set(15)
        now = 16 * minute
        assertTrue(session.tick().expired) // window A's tick sees it
        assertFalse(session.tick().expired) // window B's tick finds the timer already off
        assertEquals(1, session.expiryCount)
        assertTrue(windowA.take(session.expiryCount))
        assertTrue(windowB.take(session.expiryCount))
    }

    @Test fun eachWindowHandlesAnExpiryOnlyOnce() {
        val gate = SleepExpiryGate(session.expiryCount)
        session.set(15)
        now = 16 * minute
        session.tick()
        assertTrue(gate.take(session.expiryCount))
        assertFalse(gate.take(session.expiryCount))
        // A later run of the timer expires again and is handled again.
        session.set(15)
        now += 16 * minute
        session.tick()
        assertTrue(gate.take(session.expiryCount))
    }

    @Test fun aWindowOpenedLaterIgnoresAnOldExpiry() {
        session.set(15)
        now = 16 * minute
        session.tick()
        assertFalse(SleepExpiryGate(session.expiryCount).take(session.expiryCount))
    }

    @Test fun turningTheTimerOffIsNotAnExpiry() {
        session.set(15)
        session.set(0)
        now = 30 * minute
        session.tick()
        assertEquals(0, session.expiryCount)
    }
}
