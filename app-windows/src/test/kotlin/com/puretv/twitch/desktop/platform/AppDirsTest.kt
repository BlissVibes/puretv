package com.puretv.twitch.desktop.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AppDirsTest {
    private fun File.unix() = path.replace('\\', '/')
    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    @Test
    fun `os names map to the three desktops`() {
        assertEquals(HostOs.WINDOWS, HostOs.from("Windows 11"))
        assertEquals(HostOs.MACOS, HostOs.from("Mac OS X"))
        assertEquals(HostOs.LINUX, HostOs.from("Linux"))
        assertEquals(HostOs.LINUX, HostOs.from("FreeBSD"))
    }

    @Test
    fun `windows keeps the paths every release has used`() {
        val e = env("APPDATA" to "C:/Users/a/AppData/Roaming", "LOCALAPPDATA" to "C:/Users/a/AppData/Local")
        assertEquals("C:/Users/a/AppData/Roaming/PureTwitch", AppDirs.dataDir(HostOs.WINDOWS, e, "C:/Users/a").unix())
        assertEquals("C:/Users/a/AppData/Local/PureTwitch/cache", AppDirs.cacheDir(HostOs.WINDOWS, e, "C:/Users/a").unix())
    }

    @Test
    fun `windows without LOCALAPPDATA caches under APPDATA as before`() {
        val e = env("APPDATA" to "C:/R")
        assertEquals("C:/R/PureTwitch/cache", AppDirs.cacheDir(HostOs.WINDOWS, e, "C:/h").unix())
    }

    @Test
    fun `macos uses Library folders`() {
        assertEquals("/Users/a/Library/Application Support/PureTwitch", AppDirs.dataDir(HostOs.MACOS, env(), "/Users/a").unix())
        assertEquals("/Users/a/Library/Caches/PureTwitch", AppDirs.cacheDir(HostOs.MACOS, env(), "/Users/a").unix())
    }

    @Test
    fun `linux follows XDG with home defaults`() {
        assertEquals("/home/a/.config/PureTwitch", AppDirs.dataDir(HostOs.LINUX, env(), "/home/a").unix())
        assertEquals("/home/a/.cache/PureTwitch", AppDirs.cacheDir(HostOs.LINUX, env(), "/home/a").unix())
        val e = env("XDG_CONFIG_HOME" to "/cfg", "XDG_CACHE_HOME" to "/cch")
        assertEquals("/cfg/PureTwitch", AppDirs.dataDir(HostOs.LINUX, e, "/home/a").unix())
        assertEquals("/cch/PureTwitch", AppDirs.cacheDir(HostOs.LINUX, e, "/home/a").unix())
    }

    @Test
    fun `linux ignores relative or empty XDG values as the spec requires`() {
        val e = env("XDG_CONFIG_HOME" to "relative/cfg", "XDG_CACHE_HOME" to "")
        assertEquals("/home/a/.config/PureTwitch", AppDirs.dataDir(HostOs.LINUX, e, "/home/a").unix())
        assertEquals("/home/a/.cache/PureTwitch", AppDirs.cacheDir(HostOs.LINUX, e, "/home/a").unix())
    }
}
