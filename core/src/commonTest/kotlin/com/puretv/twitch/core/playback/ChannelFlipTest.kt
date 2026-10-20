package com.puretv.twitch.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChannelFlipTest {
    private val ring = listOf("a", "b", "c")

    @Test fun stepsAndWrapsBothWays() {
        assertEquals("b", ChannelFlip.step(ring, "a", FlipDirection.NEXT))
        assertEquals("a", ChannelFlip.step(ring, "c", FlipDirection.NEXT))
        assertEquals("c", ChannelFlip.step(ring, "a", FlipDirection.PREVIOUS))
        assertEquals("a", ChannelFlip.step(ring, "b", FlipDirection.PREVIOUS))
    }

    @Test fun loginsCompareIgnoringCase() {
        assertEquals("b", ChannelFlip.step(listOf("A", "b"), "a", FlipDirection.NEXT))
    }

    @Test fun nowhereToGoWithOneChannelOrAnUnknownCurrent() {
        assertNull(ChannelFlip.step(listOf("a"), "a", FlipDirection.NEXT))
        assertNull(ChannelFlip.step(ring, "zed", FlipDirection.NEXT))
        assertNull(ChannelFlip.step(emptyList(), "a", FlipDirection.NEXT))
    }

    @Test fun neighboursAreDistinctEvenInATwoChannelRing() {
        assertEquals(listOf("a", "c"), ChannelFlip.neighbours(ring, "b"))
        assertEquals(listOf("b"), ChannelFlip.neighbours(listOf("a", "b"), "a"))
        assertEquals(emptyList(), ChannelFlip.neighbours(listOf("a"), "a"))
    }

    @Test fun liveFollowsWinWhenTheCurrentChannelIsOneOfThem() {
        val follows = listOf("x", "y", "z")
        assertEquals(follows, ChannelFlip.ringFor("y", follows, opened = listOf("y", "q")))
    }

    @Test fun theOpenedRowIsUsedSignedOutOrWhenTheChannelIsNotAFollow() {
        val opened = listOf("p", "q", "r")
        assertEquals(opened, ChannelFlip.ringFor("q", null, opened))
        assertEquals(opened, ChannelFlip.ringFor("q", emptyList(), opened))
        assertEquals(opened, ChannelFlip.ringFor("q", listOf("x", "y"), opened))
    }

    @Test fun aRingWithoutTheCurrentChannelGetsItInFront() {
        assertEquals(listOf("lone", "p", "q"), ChannelFlip.ringFor("lone", null, listOf("p", "q")))
        assertEquals(listOf("lone"), ChannelFlip.ringFor("lone", null, emptyList()))
    }

    @Test fun duplicatesAreDroppedKeepingTheFirst() {
        assertEquals(listOf("a", "b"), ChannelFlip.ringFor("a", null, listOf("a", "A", "b", "B")))
    }

    @Test fun flipOriginDropsBlankLogins() {
        val origin = FlipOrigin()
        origin.record(listOf("a", " ", "", "b"))
        assertEquals(listOf("a", "b"), origin.logins)
    }
}

class PendingFlipTest {
    private val ring = listOf("a", "b", "c", "d")

    @Test fun aSinglePressSettlesAfterTheDelay() {
        val flip = PendingFlip(settleMs = 100)
        assertEquals("b", flip.press(ring, "a", FlipDirection.NEXT, nowMs = 1_000))
        assertEquals(1_100L, flip.dueAtMs())
        assertNull(flip.takeDue("a", nowMs = 1_050))
        assertEquals("b", flip.takeDue("a", nowMs = 1_100))
        assertNull(flip.pending)
        assertNull(flip.dueAtMs())
    }

    @Test fun rapidPressesWalkFromThePendingTargetAndSettleOnce() {
        val flip = PendingFlip(settleMs = 100)
        flip.press(ring, "a", FlipDirection.NEXT, 0)
        flip.press(ring, "a", FlipDirection.NEXT, 50)
        assertEquals("d", flip.press(ring, "a", FlipDirection.NEXT, 90))
        assertNull(flip.takeDue("a", 150))
        assertEquals("d", flip.takeDue("a", 190))
    }

    @Test fun walkingBackToTheCurrentChannelPlaysNothing() {
        val flip = PendingFlip(settleMs = 100)
        flip.press(ring, "a", FlipDirection.NEXT, 0)
        assertEquals("a", flip.press(ring, "a", FlipDirection.PREVIOUS, 10))
        assertNull(flip.takeDue("a", 200))
        assertNull(flip.pending)
    }

    @Test fun noRingMeansNoPendingFlip() {
        val flip = PendingFlip()
        assertNull(flip.press(listOf("a"), "a", FlipDirection.NEXT, 0))
        assertNull(flip.pending)
    }

    @Test fun cancelForgetsTheTarget() {
        val flip = PendingFlip()
        flip.press(ring, "a", FlipDirection.NEXT, 0)
        flip.cancel()
        assertNull(flip.takeDue("a", 10_000))
    }
}

class PrewarmGateTest {
    @Test fun aChannelIsPrewarmedOncePerWindow() {
        var now = 0L
        val gate = PrewarmGate({ now })
        assertEquals(listOf("a", "b"), gate.take(listOf("a", "b")))
        now = 44_999
        assertEquals(emptyList(), gate.take(listOf("a", "b")))
        now = 45_000
        assertEquals(listOf("a", "b"), gate.take(listOf("a", "b")))
    }

    @Test fun onlyTheStaleOnesComeBack() {
        var now = 0L
        val gate = PrewarmGate({ now })
        gate.take(listOf("a"))
        now = 30_000
        assertEquals(listOf("b"), gate.take(listOf("A", "b")))
        now = 46_000
        assertEquals(listOf("a"), gate.take(listOf("a", "b")))
    }

    @Test fun trackingIsBounded() {
        var now = 0L
        val gate = PrewarmGate({ now })
        repeat(40) { gate.take(listOf("c$it")) }
        // The oldest entries were forgotten, so they are allowed again straight away.
        assertEquals(listOf("c0"), gate.take(listOf("c0")))
    }
}
