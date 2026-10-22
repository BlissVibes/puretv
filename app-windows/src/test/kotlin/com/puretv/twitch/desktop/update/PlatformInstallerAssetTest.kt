package com.puretv.twitch.desktop.update

import com.puretv.twitch.desktop.platform.HostOs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * One release carries the Windows, macOS and Linux installers. Each OS's updater must
 * pick its own, and a Mac or Linux build must never be handed the Windows `.exe`.
 */
class PlatformInstallerAssetTest {
    private val all = GithubRelease(
        tag_name = "v1.14.0",
        assets = listOf(
            "PureTV-Setup-1.14.0.exe",
            "PureTV-Setup-1.14.0.exe.sig",
            "PureTV-for-Twitch-1.14.0-macos-arm64.dmg",
            "puretv-for-twitch_1.14.0_amd64.deb",
            "SHA256SUMS.txt",
        ).map { GithubAsset(name = it, browser_download_url = "https://github.com/x/$it", size = 1) },
    )

    @Test
    fun `windows gets the exe`() {
        assertEquals("PureTV-Setup-1.14.0.exe", all.installerAsset(HostOs.WINDOWS, "amd64")?.name)
    }

    @Test
    fun `an apple silicon mac gets the arm64 dmg`() {
        assertEquals("PureTV-for-Twitch-1.14.0-macos-arm64.dmg", all.installerAsset(HostOs.MACOS, "aarch64")?.name)
    }

    @Test
    fun `an intel mac is not offered the apple silicon build`() {
        assertNull(all.installerAsset(HostOs.MACOS, "x86_64"))
    }

    @Test
    fun `x64 linux gets the amd64 deb and arm linux gets nothing`() {
        assertEquals("puretv-for-twitch_1.14.0_amd64.deb", all.installerAsset(HostOs.LINUX, "amd64")?.name)
        assertNull(all.installerAsset(HostOs.LINUX, "aarch64"))
    }

    @Test
    fun `a windows-only release is not offered to macOS or linux`() {
        val windowsOnly = GithubRelease(
            tag_name = "v1.13.0",
            assets = listOf(GithubAsset(name = "PureTV-Setup-1.13.0.exe", browser_download_url = "https://github.com/x/a.exe")),
        )
        assertNull(latestInstallerRelease(listOf(windowsOnly), HostOs.MACOS, "aarch64"))
        assertNull(latestInstallerRelease(listOf(windowsOnly), HostOs.LINUX, "amd64"))
        assertEquals("v1.13.0", latestInstallerRelease(listOf(windowsOnly), HostOs.WINDOWS, "amd64")?.tag_name)
    }

    @Test
    fun `each os picks the newest release that has its installer`() {
        val older = all.copy(tag_name = "v1.14.0")
        val newerWindowsOnly = GithubRelease(
            tag_name = "v1.14.1",
            assets = listOf(GithubAsset(name = "PureTV-Setup-1.14.1.exe", browser_download_url = "https://github.com/x/b.exe")),
        )
        assertEquals("v1.14.0", latestInstallerRelease(listOf(newerWindowsOnly, older), HostOs.LINUX, "amd64")?.tag_name)
        assertEquals("v1.14.1", latestInstallerRelease(listOf(newerWindowsOnly, older), HostOs.WINDOWS, "amd64")?.tag_name)
    }
}
