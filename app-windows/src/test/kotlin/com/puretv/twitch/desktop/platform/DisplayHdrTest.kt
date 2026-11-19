package com.puretv.twitch.desktop.platform

import kotlin.test.Test
import kotlin.test.assertTrue

class DisplayHdrTest {
    // The query is a native user32 call: on Windows it must name real displays and never
    // throw; elsewhere it answers "nothing". Which displays have HDR on depends on the machine.
    @Test fun queriesDisplaysWithoutThrowing() {
        val displays = DisplayHdr.displays()
        if (HostOs.current == HostOs.WINDOWS) {
            assertTrue(displays.isNotEmpty(), "Windows always has at least one active display")
            assertTrue(displays.keys.all { it.contains("DISPLAY") }, "GDI names: $displays")
            println("DisplayHdr on this machine: $displays")
        } else {
            assertTrue(displays.isEmpty())
        }
    }
}
