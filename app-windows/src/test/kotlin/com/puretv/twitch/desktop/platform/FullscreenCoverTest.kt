package com.puretv.twitch.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FullscreenCoverTest {
    @Test
    fun aWholeScaleKeepsTheMonitorSize() {
        assertEquals(1920, FullscreenCover.sizePx(1920, 1.0))
        assertEquals(3840, FullscreenCover.sizePx(3840, 2.0))
        assertEquals(2160, FullscreenCover.sizePx(2160, 2.0))
    }

    @Test
    fun a4kMonitorAt175PercentGrowsToWholeUnits() {
        // 3840 / 1.75 = 2194.29 units: the next whole size is 2196 units = 3843 px.
        assertEquals(3843, FullscreenCover.sizePx(3840, 1.75))
        assertEquals(2163, FullscreenCover.sizePx(2160, 1.75))
    }

    @Test
    fun aSizeThatIsAlreadyWholeIsLeftAlone() {
        // 2560 x 1440 at 125% is 2048 x 1152 units exactly.
        assertEquals(2560, FullscreenCover.sizePx(2560, 1.25))
        assertEquals(1440, FullscreenCover.sizePx(1440, 1.25))
        // 1920 x 1080 at 150% is 1280 x 720 units exactly.
        assertEquals(1920, FullscreenCover.sizePx(1920, 1.5))
        assertEquals(1080, FullscreenCover.sizePx(1080, 1.5))
    }

    @Test
    fun theResultAlwaysCoversTheMonitorAndIsWholeInUnits() {
        for (scale in listOf(1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5, 3.0)) {
            for (monitor in listOf(1080, 1200, 1366, 1440, 1600, 1920, 2160, 2560, 3440, 3840)) {
                val px = FullscreenCover.sizePx(monitor, scale)
                assertTrue(px >= monitor, "$px covers $monitor at $scale")
                assertTrue(px - monitor < FullscreenCover.stepPx(scale), "$px is the next size up from $monitor at $scale")
                val units = px / scale
                assertTrue(Math.abs(units - Math.rint(units)) < 1e-6, "$px px is whole units at $scale")
            }
        }
    }

    @Test
    fun stepsAreTheSmallestWholeRunOfUnits() {
        assertEquals(1, FullscreenCover.stepPx(1.0))
        assertEquals(5, FullscreenCover.stepPx(1.25))
        assertEquals(3, FullscreenCover.stepPx(1.5))
        assertEquals(7, FullscreenCover.stepPx(1.75))
        assertEquals(2, FullscreenCover.stepPx(2.0))
        assertEquals(9, FullscreenCover.stepPx(2.25))
    }

    @Test
    fun anOddOrBrokenScaleLeavesTheSizeAlone() {
        assertEquals(1920, FullscreenCover.sizePx(1920, 1.1))
        assertEquals(1920, FullscreenCover.sizePx(1920, 0.0))
        assertEquals(1920, FullscreenCover.sizePx(1920, Double.NaN))
    }
}
