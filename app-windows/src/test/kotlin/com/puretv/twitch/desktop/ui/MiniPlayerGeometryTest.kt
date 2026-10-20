package com.puretv.twitch.desktop.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiniPlayerGeometryTest {
    // A 1920x1080 screen with a 40 high taskbar at the bottom.
    private val work = MiniRect(0, 0, 1920, 1040)

    @Test fun windowHeightIsA169VideoPlusTheStrip() {
        assertEquals(270 + MiniPlayerGeometry.STRIP_HEIGHT, MiniPlayerGeometry.heightFor(480))
        assertEquals(480, MiniPlayerGeometry.widthFor(270 + MiniPlayerGeometry.STRIP_HEIGHT))
    }

    @Test fun defaultsToTheBottomRightAboveTheTaskbar() {
        val b = MiniPlayerGeometry.defaultBounds(work)
        assertEquals(480, b.width)
        assertEquals(MiniPlayerGeometry.heightFor(480), b.height)
        assertEquals(1920 - 480 - MiniPlayerGeometry.MARGIN, b.x)
        assertEquals(1040 - b.height - MiniPlayerGeometry.MARGIN, b.y)
    }

    @Test fun respectsAWorkAreaThatDoesNotStartAtTheOrigin() {
        // A second monitor to the left, with the taskbar on its left edge.
        val left = MiniRect(-1920 + 60, 0, 1920 - 60, 1080)
        val b = MiniPlayerGeometry.defaultBounds(left)
        assertTrue(b.x >= left.x && b.x + b.width <= left.x + left.width)
        assertEquals(left.x + left.width - b.width - MiniPlayerGeometry.MARGIN, b.x)
    }

    @Test fun aTinyScreenStillGetsAWindowThatFits() {
        val tiny = MiniRect(0, 0, 300, 200)
        val b = MiniPlayerGeometry.defaultBounds(tiny)
        assertTrue(b.x >= 0 && b.y >= 0)
        assertTrue(b.x + b.width <= 300 && b.y + b.height <= 200)
    }

    @Test fun widthIsClampedToTheLimitsAndTheScreen() {
        assertEquals(MiniPlayerGeometry.MIN_WIDTH, MiniPlayerGeometry.clampWidth(100, work))
        assertEquals(MiniPlayerGeometry.MAX_WIDTH, MiniPlayerGeometry.clampWidth(5000, work))
        assertEquals(500, MiniPlayerGeometry.clampWidth(900, MiniRect(0, 0, 500, 600)))
    }

    @Test fun clampKeepsTheWholeWindowOnTheScreen() {
        val b = MiniPlayerGeometry.clampToWork(MiniRect(1800, 1000, 480, 314), work)
        assertEquals(1920 - 480, b.x)
        assertEquals(1040 - 314, b.y)
        assertEquals(MiniRect(0, 0, 480, 314), MiniPlayerGeometry.clampToWork(MiniRect(-50, -50, 480, 314), work))
    }

    @Test fun aRememberedSpotIsKeptWhileItsScreenIsStillThere() {
        val saved = MiniRect(100, 200, 640, MiniPlayerGeometry.heightFor(640))
        assertEquals(saved, MiniPlayerGeometry.initialBounds(saved, listOf(work), work))
    }

    @Test fun aRememberedSpotOnAMissingScreenFallsBackToTheCorner() {
        val saved = MiniRect(3000, 100, 640, MiniPlayerGeometry.heightFor(640))
        assertEquals(MiniPlayerGeometry.defaultBounds(work), MiniPlayerGeometry.initialBounds(saved, listOf(work), work))
        assertEquals(MiniPlayerGeometry.defaultBounds(work), MiniPlayerGeometry.initialBounds(null, listOf(work), work))
    }

    @Test fun aRememberedSpotHangingOverTheEdgeIsPulledBack() {
        val saved = MiniRect(1800, 1000, 640, MiniPlayerGeometry.heightFor(640))
        val b = MiniPlayerGeometry.initialBounds(saved, listOf(work), work)
        assertTrue(b.x + b.width <= 1920 && b.y + b.height <= 1040)
    }

    @Test fun resizingStaysSixteenByNine() {
        val h = MiniPlayerGeometry.heightFor(480)
        // Dragged wider: height follows.
        assertEquals(640 to MiniPlayerGeometry.heightFor(640), MiniPlayerGeometry.fitSize(640, h, 480, h, work))
        // Dragged taller: width follows.
        val tall = MiniPlayerGeometry.heightFor(640)
        assertEquals(640 to tall, MiniPlayerGeometry.fitSize(480, tall, 480, h, work))
        // A corner moves both: width wins.
        assertEquals(560 to MiniPlayerGeometry.heightFor(560), MiniPlayerGeometry.fitSize(560, 900, 480, h, work))
    }

    @Test fun resizingNeverGoesBelowTheMinimum() {
        val h = MiniPlayerGeometry.heightFor(480)
        val (w, hh) = MiniPlayerGeometry.fitSize(100, h, 480, h, work)
        assertEquals(MiniPlayerGeometry.MIN_WIDTH, w)
        assertEquals(MiniPlayerGeometry.heightFor(MiniPlayerGeometry.MIN_WIDTH), hh)
    }

    @Test fun twoQuickClicksAreADoubleClick() {
        val d = DoubleClickDetector(400)
        assertFalse(d.click(1_000))
        assertTrue(d.click(1_300))
        // The pair is used up: a third click starts over.
        assertFalse(d.click(1_350))
    }

    @Test fun slowClicksAreNotADoubleClick() {
        val d = DoubleClickDetector(400)
        assertFalse(d.click(1_000))
        assertFalse(d.click(1_500))
        assertTrue(d.click(1_600))
    }

    @Test fun theSpotIsRememberedAcrossRuns() {
        val dir = kotlin.io.path.createTempDirectory("mini").toFile()
        try {
            val file = File(dir, "mini-player.txt")
            assertNull(MiniBoundsStore(file).load())
            MiniBoundsStore(file).save(MiniRect(120, 340, 640, 0))
            assertEquals(MiniRect(120, 340, 640, MiniPlayerGeometry.heightFor(640)), MiniBoundsStore(file).load())
            file.writeText("not numbers")
            assertNull(MiniBoundsStore(file).load())
        } finally {
            dir.deleteRecursively()
        }
    }
}
