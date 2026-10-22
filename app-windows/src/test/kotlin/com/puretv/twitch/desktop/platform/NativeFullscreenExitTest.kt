package com.puretv.twitch.desktop.platform

import androidx.compose.ui.window.WindowPlacement
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeFullscreenExitTest {
    @Test
    fun `leaving a reached fullscreen is noticed once`() {
        val exit = NativeFullscreenExit()
        assertFalse(exit.left(true, WindowPlacement.Fullscreen))
        assertTrue(exit.left(true, WindowPlacement.Floating))
        assertFalse(exit.left(true, WindowPlacement.Floating))
    }

    @Test
    fun `a normal placement before fullscreen is reached is not an exit`() {
        // macOS animates into fullscreen: the placement can still read Floating right after the ask.
        val exit = NativeFullscreenExit()
        assertFalse(exit.left(true, WindowPlacement.Floating))
        assertFalse(exit.left(true, WindowPlacement.Fullscreen))
        assertTrue(exit.left(true, WindowPlacement.Maximized))
    }

    @Test
    fun `nothing is noticed while PureTV is not fullscreen`() {
        val exit = NativeFullscreenExit()
        assertFalse(exit.left(false, WindowPlacement.Fullscreen))
        assertFalse(exit.left(false, WindowPlacement.Floating))
        // Asked again later: the earlier fullscreen does not count.
        assertFalse(exit.left(true, WindowPlacement.Floating))
    }
}
