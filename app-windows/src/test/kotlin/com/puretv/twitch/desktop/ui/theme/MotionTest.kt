package com.puretv.twitch.desktop.ui.theme

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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
    fun springsUseTheExpressiveTokensAndEffectsNeverOvershoot() {
        assertEquals(0.6f, PureTvMotion.MorphSpring.dampingRatio)
        assertEquals(800f, PureTvMotion.MorphSpring.stiffness)
        assertEquals(0.6f, PureTvMotion.PressSpring.dampingRatio)
        val hover = assertIs<SpringSpec<Float>>(PureTvMotion.effectsFast<Float>())
        assertEquals(1f, hover.dampingRatio)
        assertEquals(3800f, hover.stiffness)
        val state = assertIs<SpringSpec<Float>>(PureTvMotion.effects<Float>())
        assertEquals(1f, state.dampingRatio)
        assertEquals(1600f, state.stiffness)
    }

    @Test
    fun reducedMotionDropsMovementButKeepsColour() {
        PureTvMotion.reduced = true
        assertNull(PureTvMotion.itemPlacement())
        assertIs<SnapSpec<*>>(PureTvMotion.hover())
        assertIs<SpringSpec<Float>>(PureTvMotion.effectsFast<Float>())
        assertIs<SpringSpec<Float>>(PureTvMotion.itemFadeOut())
    }

    @Test
    fun sidePanelTakesItsWidthAtOnceAndGivesItBackAfterTheFade() {
        assertEquals(0, assertIs<SnapSpec<*>>(PureTvMotion.sidePanelSize<Float>(open = true)).delay)
        assertEquals(PureTvMotion.Fast, assertIs<SnapSpec<*>>(PureTvMotion.sidePanelSize<Float>(open = false)).delay)
        assertEquals(0, assertIs<SnapSpec<*>>(PureTvMotion.sidePanelSize<Float>(open = false, instant = true)).delay)
        assertEquals(PureTvMotion.Fast, assertIs<TweenSpec<Float>>(PureTvMotion.sidePanelReveal(open = false)).durationMillis)
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
