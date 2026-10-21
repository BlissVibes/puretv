package com.puretv.twitch.core.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionTokensTest {
    @Test
    fun staggerGrowsThenCaps() {
        assertEquals(0, MotionTokens.staggerDelayMs(0))
        assertEquals(30, MotionTokens.staggerDelayMs(1))
        assertEquals(240, MotionTokens.staggerDelayMs(8))
        assertEquals(240, MotionTokens.staggerDelayMs(40))
    }

    @Test
    fun staggerIsZeroWhenReducedOrNegative() {
        assertEquals(0, MotionTokens.staggerDelayMs(5, reduced = true))
        assertEquals(0, MotionTokens.staggerDelayMs(-3))
    }

    @Test
    fun onlyAZeroAnimatorScaleMeansReducedMotion() {
        assertTrue(MotionTokens.isReducedMotion(0f))
        assertFalse(MotionTokens.isReducedMotion(1f))
        assertFalse(MotionTokens.isReducedMotion(0.5f))
        assertFalse(MotionTokens.isReducedMotion(-1f))
    }

    @Test
    fun scaledDurationFollowsTheAnimatorScale() {
        assertEquals(0, MotionTokens.scaledDurationMs(220, 0f))
        assertEquals(220, MotionTokens.scaledDurationMs(220, 1f))
        assertEquals(110, MotionTokens.scaledDurationMs(220, 0.5f))
        assertEquals(440, MotionTokens.scaledDurationMs(220, 2f))
        assertEquals(220, MotionTokens.scaledDurationMs(220, -1f))
        assertEquals(1, MotionTokens.scaledDurationMs(1, 0.1f))
    }

    @Test
    fun exitsAreNotSlowerThanEntrances() {
        assertTrue(MotionTokens.ExitMs <= MotionTokens.MediumMs)
        assertTrue(MotionTokens.FastMs < MotionTokens.MediumMs && MotionTokens.MediumMs < MotionTokens.SlowMs)
    }

    @Test
    fun centringScrollsByTheGapToCentre() {
        // A 200 wide card at 700 in a 1000 container sits at its centre target (400): scroll 300.
        assertEquals(300f, MotionTokens.centredScrollDistance(700f, 200f, 1000f))
        assertEquals(0f, MotionTokens.centredScrollDistance(400f, 200f, 1000f))
        assertEquals(-250f, MotionTokens.centredScrollDistance(150f, 200f, 1000f))
    }

    @Test
    fun anOversizedChildAlignsItsLeadingEdge() {
        assertEquals(40f, MotionTokens.centredScrollDistance(40f, 1200f, 1000f))
        assertEquals(40f, MotionTokens.bandedScrollDistance(40f, 1200f, 1000f, 0.18f))
    }

    @Test
    fun theBandHoldsStillInsideAndCentresOutside() {
        assertEquals(0f, MotionTokens.bandedScrollDistance(300f, 200f, 1000f, 0.18f))
        // Child bottom at 900 passes the 820 limit, so the page glides to centre it.
        assertEquals(300f, MotionTokens.bandedScrollDistance(700f, 200f, 1000f, 0.18f))
        // Child top at 100 is above the 180 margin.
        assertEquals(-300f, MotionTokens.bandedScrollDistance(100f, 200f, 1000f, 0.18f))
    }
}
