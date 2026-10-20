package com.puretv.twitch.core.adblock

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multi-stream shows one ad-block pill per tile, so each channel reports on its
 * own while the app-wide status keeps behaving exactly as it did.
 */
class AdBlockChannelStatusTest {

    private fun engine(): AdBlockEngine =
        AdBlockEngine(AdBlockConfig(), HttpClient(MockEngine { respond("", HttpStatusCode.OK) }))

    private val clean = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:2.0,live\nhttps://x.ttvnw.net/a.ts\n"

    @Test
    fun aChannelWithNoReportYetIsChecking() = runTest {
        assertEquals(AdBlockStatus.UNKNOWN, engine().statusFor("shroud").first())
    }

    @Test
    fun twoChannelsReportIndependently() = runTest {
        val e = engine()
        e.reportAdBlocked("shroud")
        e.reportFiltered(e.filterPlaylist(AD_PLAYLIST), "xqc")

        assertEquals(AdBlockStatus.AD_BLOCKED, e.statusFor("shroud").first())
        assertEquals(AdBlockStatus.AD_FILTERED, e.statusFor("xqc").first())
        assertEquals(AdBlockStatus.UNKNOWN, e.statusFor("pokimane").first())
    }

    @Test
    fun channelKeysIgnoreCase() = runTest {
        val e = engine()
        e.reportResolutionFailure("Shroud")
        assertEquals(AdBlockStatus.AD_BLOCK_OFF, e.statusFor("SHROUD").first())
    }

    @Test
    fun theAppWideStatusStillFollowsEveryReport() {
        val e = engine()
        e.reportAdBlocked("shroud")
        assertEquals(AdBlockStatus.AD_BLOCKED, e.status.value)
        e.reportResolutionFailure("xqc")
        assertEquals(AdBlockStatus.AD_BLOCK_OFF, e.status.value)
        // A report with no channel (phone, TV, single stream) leaves the channel map alone.
        e.reportAdBlocked()
        assertEquals(AdBlockStatus.AD_BLOCKED, e.status.value)
    }

    @Test
    fun resetForNewStreamOnlyResetsThatChannel() = runTest {
        val e = engine()
        e.reportAdBlocked("shroud")
        e.reportAdBlocked("xqc")
        e.resetForNewStream("shroud")
        assertEquals(AdBlockStatus.UNKNOWN, e.statusFor("shroud").first())
        assertEquals(AdBlockStatus.AD_BLOCKED, e.statusFor("xqc").first())
    }

    @Test
    fun disablingMovesEveryKnownChannelToDisabled() = runTest {
        val e = engine()
        e.reportAdBlocked("shroud")
        e.setEnabled(false)
        assertEquals(AdBlockStatus.DISABLED, e.statusFor("shroud").first())
        assertEquals(AdBlockStatus.DISABLED, e.status.value)
    }

    @Test
    fun forgettingAChannelClearsItsStatus() = runTest {
        val e = engine()
        e.reportAdBlocked("shroud")
        e.forgetChannel("shroud")
        assertEquals(AdBlockStatus.UNKNOWN, e.statusFor("shroud").first())
    }

    @Test
    fun aLateReportFromAClosedTileDoesNotTouchThePill() = runTest {
        val e = engine()
        e.reportAdBlocked("shroud")
        e.forgetChannel("shroud")
        e.resetForNewStream()
        e.reportFiltered(e.filterPlaylist(AD_PLAYLIST), "shroud")
        assertEquals(AdBlockStatus.UNKNOWN, e.status.value)
        assertEquals(AdBlockStatus.UNKNOWN, e.statusFor("shroud").first())
    }

    @Test
    fun aReleasedChannelReportsAgainOnceItIsPlayedAgain() = runTest {
        val e = engine()
        e.forgetChannel("shroud")
        e.claimChannel("shroud")
        e.reportAdBlocked("shroud")
        assertEquals(AdBlockStatus.AD_BLOCKED, e.status.value)
        assertEquals(AdBlockStatus.AD_BLOCKED, e.statusFor("shroud").first())
    }

    @Test
    fun theCleanerReportsOnTheChannelItWasGiven() = runTest {
        val e = engine()
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
        val cleaner = MediaPlaylistCleaner(
            adBlockEngine = e,
            backupStreamResolver = BackupStreamResolver(
                http,
                com.puretv.twitch.core.stream.StreamResolver(http, com.puretv.twitch.core.stream.TwitchGqlClient(http)),
            ),
            httpClient = http,
            nowMs = { 0L },
            holdEmptyWindows = false,
        )
        cleaner.clean("https://x.ttvnw.net/v/shroud.m3u8", clean, channelLogin = "shroud", quality = null)
        cleaner.clean("https://x.ttvnw.net/v/xqc.m3u8", AD_PLAYLIST, channelLogin = "xqc", quality = null)

        assertEquals(AdBlockStatus.AD_BLOCKED, e.statusFor("shroud").first())
        assertEquals(AdBlockStatus.AD_FILTERED, e.statusFor("xqc").first())
    }

    private val AD_PLAYLIST = """
        #EXTM3U
        #EXT-X-TARGETDURATION:2
        #EXT-X-MEDIA-SEQUENCE:100
        #EXT-X-DATERANGE:ID="stitch-1",CLASS="twitch-stitched-ad",START-DATE="2026-01-01T00:00:00.000Z",DURATION=4.0
        #EXTINF:2.0,Amazon
        https://x.ttvnw.net/ad-0.ts
        #EXTINF:2.0,Amazon
        https://x.ttvnw.net/ad-1.ts
        #EXT-X-DISCONTINUITY
        #EXTINF:2.0,live
        https://x.ttvnw.net/content-100.ts
    """.trimIndent() + "\n"
}
