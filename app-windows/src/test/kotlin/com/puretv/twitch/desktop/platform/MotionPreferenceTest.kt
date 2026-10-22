package com.puretv.twitch.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MotionPreferenceTest {
    @Test
    fun `macOS reduce motion on means animations off`() {
        assertEquals(false, SystemAnimations.parseMacReduceMotion("1\n"))
        assertEquals(true, SystemAnimations.parseMacReduceMotion("0\n"))
        // Never set: `defaults` fails and prints an error, which is not an answer.
        assertNull(SystemAnimations.parseMacReduceMotion("The domain/default pair does not exist"))
        assertNull(SystemAnimations.parseMacReduceMotion(null))
    }

    @Test
    fun `gnome animations switch is read as is`() {
        assertEquals(true, SystemAnimations.parseGnomeAnimations("true\n"))
        assertEquals(false, SystemAnimations.parseGnomeAnimations("false\n"))
        assertNull(SystemAnimations.parseGnomeAnimations("No such schema"))
    }

    @Test
    fun `shortcut modifier is Cmd only on macOS`() {
        assertEquals("Cmd", ShortcutKey.label(HostOs.MACOS))
        assertEquals("Ctrl", ShortcutKey.label(HostOs.WINDOWS))
        assertEquals("Ctrl", ShortcutKey.label(HostOs.LINUX))
    }
}
