package com.puretv.twitch.desktop.ui.theme

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MotionTest {
    @AfterTest
    fun restore() {
        PureTvMotion.reduced = false
    }

    @Test
    fun durationsAreOrderedAndShortEnoughToFeelInstant() {
        assertTrue(PureTvMotion.Fast < PureTvMotion.Medium)
        assertTrue(PureTvMotion.Medium < PureTvMotion.Slow)
        assertTrue(PureTvMotion.Slow <= 400)
    }

    @Test
    fun staggerDelayGrowsPerItemAndStopsAtTheCap() {
        assertEquals(0, PureTvMotion.staggerDelayMs(0))
        assertEquals(PureTvMotion.StaggerStepMs, PureTvMotion.staggerDelayMs(1))
        val cap = PureTvMotion.StaggerMaxItems * PureTvMotion.StaggerStepMs
        assertEquals(cap, PureTvMotion.staggerDelayMs(PureTvMotion.StaggerMaxItems))
        assertEquals(cap, PureTvMotion.staggerDelayMs(500))
        assertEquals(0, PureTvMotion.staggerDelayMs(-3))
    }

    @Test
    fun moveSnapsWhenReducedButFadeKeepsItsTween() {
        assertIs<TweenSpec<Float>>(PureTvMotion.move<Float>())
        PureTvMotion.reduced = true
        assertIs<SnapSpec<Float>>(PureTvMotion.move<Float>())
        assertIs<SnapSpec<Float>>(PureTvMotion.press())
        assertIs<SnapSpec<*>>(PureTvMotion.morph())
        assertIs<TweenSpec<Float>>(PureTvMotion.fade<Float>())
    }

    @Test
    fun routeDirectionFollowsDepth() {
        assertEquals(1, routeDirection(RouteDepth.Top, RouteDepth.Detail))
        assertEquals(1, routeDirection(RouteDepth.Top, RouteDepth.Player))
        assertEquals(-1, routeDirection(RouteDepth.Player, RouteDepth.Detail))
        assertEquals(-1, routeDirection(RouteDepth.Detail, RouteDepth.Top))
        assertEquals(0, routeDirection(RouteDepth.Top, RouteDepth.Top))
    }
}
