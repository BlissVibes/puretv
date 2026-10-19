package com.puretv.twitch.desktop.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsTest {
    @Test
    fun signedPlaybackUrlsLoseTheirTokens() {
        val line = "usher refused https://usher.ttvnw.net/api/channel/hls/x.m3u8?sig=abc123&token=%7B%22a%22%7D&allow_source=true"
        val scrubbed = Diagnostics.scrub(line)

        assertFalse("abc123" in scrubbed)
        assertFalse("%7B%22a%22%7D" in scrubbed)
        assertTrue("allow_source=true" in scrubbed, "harmless parameters stay readable")
    }

    @Test
    fun authorizationValuesAreRemoved() {
        val scrubbed = Diagnostics.scrub("Authorization: Bearer abcdefghijklmnop1234 refresh_token=xyz")

        assertFalse("abcdefghijklmnop1234" in scrubbed)
        assertFalse("xyz" in scrubbed)
    }

    @Test
    fun theReportCarriesTheAppSectionsScrubbed() {
        val report = Diagnostics.report(listOf("Last stream failure" to "offline: https://x/y.m3u8?token=secret"))

        assertTrue("== Last stream failure" in report)
        assertFalse("secret" in report)
    }
}
