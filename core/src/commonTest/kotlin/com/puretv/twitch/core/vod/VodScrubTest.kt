package com.puretv.twitch.core.vod

import kotlin.test.Test
import kotlin.test.assertEquals

class VodScrubTest {
    @Test fun stepGrowsWhileHeld() {
        assertEquals(10_000L, VodScrub.stepFor(0))
        assertEquals(10_000L, VodScrub.stepFor(5))
        assertEquals(30_000L, VodScrub.stepFor(6))
        assertEquals(30_000L, VodScrub.stepFor(15))
        assertEquals(60_000L, VodScrub.stepFor(16))
    }

    @Test fun firstMoveStartsFromThePlayingPosition() =
        assertEquals(70_000L, VodScrub.move(pendingMs = null, positionMs = 60_000, durationMs = 600_000, deltaMs = 10_000))

    @Test fun laterMovesStartFromThePendingPosition() =
        assertEquals(130_000L, VodScrub.move(pendingMs = 100_000, positionMs = 60_000, durationMs = 600_000, deltaMs = 30_000))

    @Test fun clampsToTheVideo() {
        assertEquals(600_000L, VodScrub.move(pendingMs = 590_000, positionMs = 0, durationMs = 600_000, deltaMs = 60_000))
        assertEquals(0L, VodScrub.move(pendingMs = null, positionMs = 4_000, durationMs = 600_000, deltaMs = -10_000))
    }

    @Test fun unknownLengthOnlyClampsAtZero() =
        assertEquals(90_000L, VodScrub.move(pendingMs = null, positionMs = 30_000, durationMs = 0, deltaMs = 60_000))
}
