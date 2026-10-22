package com.puretv.twitch.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserCommandTest {
    @Test
    fun `linux and macOS hand https links to their opener`() {
        assertEquals(listOf("xdg-open", "https://www.twitch.tv/activate"), browserCommand("https://www.twitch.tv/activate", HostOs.LINUX))
        assertEquals(listOf("open", "https://github.com/dhawal-ss/puretv/releases"), browserCommand("https://github.com/dhawal-ss/puretv/releases", HostOs.MACOS))
    }

    @Test
    fun `windows keeps using the java launcher only`() {
        assertNull(browserCommand("https://www.twitch.tv/activate", HostOs.WINDOWS))
    }

    @Test
    fun `anything but an https link never reaches the command line`() {
        assertNull(browserCommand("file:///etc/passwd", HostOs.LINUX))
        assertNull(browserCommand("--help", HostOs.LINUX))
        assertNull(browserCommand("http://example.com", HostOs.MACOS))
        assertNull(browserCommand("https://", HostOs.LINUX))
        assertNull(browserCommand("not a url", HostOs.LINUX))
    }
}
