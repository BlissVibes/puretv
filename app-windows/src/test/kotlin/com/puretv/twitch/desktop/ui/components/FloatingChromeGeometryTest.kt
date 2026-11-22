package com.puretv.twitch.desktop.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FloatingChromeGeometryTest {
    private val g = FloatingChromeGeometry

    @Test
    fun rolledUpNothingShows() {
        assertTrue(g.strips(slotWidth = 800, slotHeight = 450, top = 72, bottom = 80, progress = 0f).isEmpty())
    }

    @Test
    fun unrolledBothBarsShowWholeAtTheirEdges() {
        val strips = g.strips(slotWidth = 800, slotHeight = 450, top = 72, bottom = 80, progress = 1f)
        assertEquals(listOf(FloatingChromeGeometry.Box(0, 0, 800, 72), FloatingChromeGeometry.Box(0, 370, 800, 80)), strips)
    }

    @Test
    fun halfwayTheTopGrowsDownAndTheBottomGrowsUp() {
        val strips = g.strips(slotWidth = 800, slotHeight = 450, top = 72, bottom = 80, progress = 0.5f)
        // The top strip still starts at the top edge; the bottom strip still ends at the bottom edge.
        assertEquals(FloatingChromeGeometry.Box(0, 0, 800, 36), strips[0])
        assertEquals(FloatingChromeGeometry.Box(0, 410, 800, 40), strips[1])
        assertEquals(450, strips[1].y + strips[1].height)
    }

    @Test
    fun aBarTallerThanTheSlotStopsAtTheSlot() {
        val strips = g.strips(slotWidth = 800, slotHeight = 300, top = 72, bottom = 900, progress = 1f)
        assertEquals(FloatingChromeGeometry.Box(0, 0, 800, 300), strips[1])
    }

    @Test
    fun aBarThatHasNotBeenMeasuredYetShowsNothing() {
        assertTrue(g.strips(slotWidth = 800, slotHeight = 450, top = 0, bottom = 0, progress = 1f).isEmpty())
    }

    @Test
    fun theWindowSitsOnTheSlotInScreenUnits() {
        // 200% scale, owner content at (100, 50): a slot of 400..1200 x 300..900 px.
        val b = g.windowBounds(400f, 300f, 1200f, 900f, density = 2f, originX = 100, originY = 50)
        assertEquals(FloatingChromeGeometry.Box(300, 200, 400, 300), b)
    }

    @Test
    fun atAFractionalScaleTheWindowIsAWholeNumberOfPixels() {
        // 175%: the slot is 3140 x 1894 px = 1794.3 x 1082.3 units, so 1795 x 1083 units,
        // rounded up to runs of 4 units (7 px): 1796 x 1084.
        val b = g.windowBounds(0f, 126f, 3140f, 2020f, density = 1.75f, originX = 0, originY = 0)
        assertEquals(FloatingChromeGeometry.Box(0, 72, 1796, 1084), b)
        assertEquals(0f, (b.width * 1.75f) % 1f)
        assertEquals(0f, (b.height * 1.75f) % 1f)
    }

    @Test
    fun wholePixelRunsPerScale() {
        assertEquals(1, g.wholePixelUnits(1f))
        assertEquals(4, g.wholePixelUnits(1.25f))
        assertEquals(2, g.wholePixelUnits(1.5f))
        assertEquals(4, g.wholePixelUnits(1.75f))
        assertEquals(1, g.wholePixelUnits(2f))
        assertEquals(1, g.wholePixelUnits(0f))
    }
}
