package com.puretv.twitch.desktop.ui

import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativePointerActivityTest {
    private val window = Rectangle(0, 0, 2560, 1440)

    @Test
    fun theFirstReadingIsNotAMove() {
        assertFalse(PointerActivity.moved(null, Point(10, 10), window))
    }

    @Test
    fun aPointerThatStaysPutIsNotAMove() {
        assertFalse(PointerActivity.moved(Point(10, 10), Point(10, 10), window))
    }

    @Test
    fun aNewPositionInsideTheWindowIsAMove() {
        assertTrue(PointerActivity.moved(Point(10, 10), Point(11, 10), window))
    }

    @Test
    fun movingOnAnotherMonitorIsNotAMoveHere() {
        assertFalse(PointerActivity.moved(Point(10, 10), Point(-500, 10), window))
    }

    @Test
    fun aWindowOnANegativeMonitorStillCounts() {
        val left = Rectangle(-1920, 0, 1920, 1080)
        assertTrue(PointerActivity.moved(Point(-100, 5), Point(-90, 5), left))
    }

    @Test
    fun noWindowBoundsMeansAnyMoveCounts() {
        assertTrue(PointerActivity.moved(Point(1, 1), Point(2, 2), null))
    }

    @Test
    fun anUnreadablePointerIsNotAMove() {
        assertFalse(PointerActivity.moved(Point(1, 1), null, window))
    }
}
