package com.puretv.twitch.desktop.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoginStartupTest {
    private fun File.unix() = path.replace('\\', '/')

    @Test
    fun `linux entry lives in the XDG autostart folder`() {
        assertEquals("/home/a/.config/autostart/puretv-for-twitch.desktop", LoginStartup.linuxEntry(File("/home/a"), null).unix())
        assertEquals("/cfg/autostart/puretv-for-twitch.desktop", LoginStartup.linuxEntry(File("/home/a"), "/cfg").unix())
        assertEquals("/home/a/.config/autostart/puretv-for-twitch.desktop", LoginStartup.linuxEntry(File("/home/a"), "rel").unix())
    }

    @Test
    fun `linux entry quotes the launcher path that has spaces`() {
        val entry = LoginStartup.linuxDesktopEntry("/opt/puretv-for-twitch/bin/PureTV for Twitch")
        assertTrue("Exec=\"/opt/puretv-for-twitch/bin/PureTV for Twitch\" --background\n" in entry, entry)
        assertTrue(entry.startsWith("[Desktop Entry]\n"))
    }

    @Test
    fun `linux entry escapes characters the desktop spec reserves inside quotes`() {
        val entry = LoginStartup.linuxDesktopEntry("/x/a\"b\$c")
        assertTrue("Exec=\"/x/a\\\"b\\\$c\" --background" in entry, entry)
    }

    @Test
    fun `mac agent runs the app with the background flag at load`() {
        val plist = LoginStartup.macLaunchAgent("/Applications/PureTV for Twitch.app/Contents/MacOS/PureTV for Twitch")
        assertTrue(plist.startsWith("<?xml"))
        assertTrue("<string>/Applications/PureTV for Twitch.app/Contents/MacOS/PureTV for Twitch</string>" in plist)
        assertTrue("<string>--background</string>" in plist)
        assertTrue("<key>RunAtLoad</key>" in plist)
        assertEquals("/Users/a/Library/LaunchAgents/com.puretv.twitch.plist", LoginStartup.macAgent(File("/Users/a")).unix())
    }

    @Test
    fun `each os names the switch its own way`() {
        assertEquals("Start with Windows", LoginStartup.label(HostOs.WINDOWS))
        assertEquals("Open at login", LoginStartup.label(HostOs.MACOS))
    }
}
