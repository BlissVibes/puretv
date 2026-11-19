package com.puretv.twitch.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LaunchArgsTest {
    @Test
    fun `both spellings open the channel in lower case`() {
        assertEquals("forsen", LaunchArgs.channel(arrayOf("--channel", "Forsen")))
        assertEquals("forsen", LaunchArgs.channel(arrayOf("--background", "--channel=forsen")))
    }

    @Test
    fun `missing or invalid logins are ignored`() {
        assertNull(LaunchArgs.channel(arrayOf()))
        assertNull(LaunchArgs.channel(arrayOf("--channel")))
        assertNull(LaunchArgs.channel(arrayOf("--channel", "../etc/passwd")))
        assertNull(LaunchArgs.channel(arrayOf("--channel=a b")))
        assertNull(LaunchArgs.channel(arrayOf("--channel", "x".repeat(26))))
    }

    @Test
    fun `a second launch hands its channel to the running copy`() {
        assertEquals("channel:forsen", LaunchArgs.request(arrayOf("--channel", "Forsen")))
        assertEquals("show", LaunchArgs.request(arrayOf()))
        assertEquals("forsen", LaunchArgs.channelFromRequest("channel:forsen\n"))
        assertNull(LaunchArgs.channelFromRequest("show"))
        assertNull(LaunchArgs.channelFromRequest("channel:../x"))
        assertNull(LaunchArgs.channelFromRequest("forsen"))
    }
}
