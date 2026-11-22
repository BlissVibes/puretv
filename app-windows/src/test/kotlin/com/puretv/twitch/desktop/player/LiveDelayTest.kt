package com.puretv.twitch.desktop.player

import com.puretv.twitch.core.stream.HlsLiveTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveDelayTest {
    /** Fourteen 2 s segments from broadcast time 100_000; the newest starts at 126_000. */
    private val timing = HlsLiveTiming(
        segments = (0 until 14).map { HlsLiveTiming.Segment(100_000L + 2000L * it, 2000) },
        serverTimeMs = 131_000,
    )

    /** Twitch's clock reads 131_500 at monotonic 50_000. */
    private val server = 81_500L

    @Test
    fun aStartIsTiedToWhereVlcBeginsInThePlaylist() {
        // Low latency (6 s): VLC begins at the segment holding 126_000 - 6_000 = 120_000,
        // so a first picture at 131_500 on Twitch's clock is 11.5 s behind.
        val tracker = LiveDelayTracker(liveDelayMs = 6_000)
        assertTrue(tracker.start(timing, monoMs = 50_000))
        assertEquals(11_500, tracker.delayMs(server))
    }

    @Test
    fun theDefaultLiveDelayStartsFurtherBack() {
        // 15 s: the segment holding 126_000 - 15_000 = 111_000 starts at 110_000.
        val tracker = LiveDelayTracker(liveDelayMs = 15_000)
        tracker.start(timing, monoMs = 50_000)
        assertEquals(21_500, tracker.delayMs(server))
    }

    @Test
    fun theDelayStaysPutWhileThePictureIsHeld() {
        val tracker = LiveDelayTracker(liveDelayMs = 6_000)
        tracker.start(timing, monoMs = 50_000)
        tracker.hold(monoMs = 60_000)
        assertEquals(11_500, tracker.delayMs(server))
    }

    @Test
    fun aPauseIsAddedOnceThePictureMovesAgain() {
        val tracker = LiveDelayTracker(liveDelayMs = 6_000)
        tracker.start(timing, monoMs = 50_000)
        tracker.hold(monoMs = 60_000)
        tracker.release(monoMs = 64_250)
        assertEquals(15_750, tracker.delayMs(server))
    }

    @Test
    fun aSecondHoldBeforeTheReleaseDoesNotRestartTheCount() {
        // A pause, then the buffering that follows play: one stretch, counted from the pause.
        val tracker = LiveDelayTracker(liveDelayMs = 6_000)
        tracker.start(timing, monoMs = 50_000)
        tracker.hold(monoMs = 60_000)
        tracker.hold(monoMs = 63_000)
        tracker.release(monoMs = 64_000)
        tracker.release(monoMs = 64_500)
        assertEquals(15_500, tracker.delayMs(server))
    }

    @Test
    fun afterALongHoldTheDelayIsNotKnown() {
        val tracker = LiveDelayTracker(liveDelayMs = 6_000)
        tracker.start(timing, monoMs = 50_000)
        tracker.hold(monoMs = 60_000)
        tracker.release(monoMs = 60_000 + 45_000)
        assertNull(tracker.delayMs(server))
    }

    @Test
    fun nothingUntilTheStartIsKnown() {
        val tracker = LiveDelayTracker(liveDelayMs = 6_000)
        assertNull(tracker.delayMs(server))
        tracker.hold(1_000)
        tracker.release(2_000)
        assertNull(tracker.delayMs(server))
        assertFalse(tracker.start(HlsLiveTiming(emptyList(), null), 0))
    }

    @Test
    fun theDelayIsNeverNegative() {
        val tracker = LiveDelayTracker(liveDelayMs = 6_000)
        tracker.start(timing, monoMs = 50_000)
        assertEquals(0, tracker.delayMs(serverMinusMonoMs = 10))
    }

    private fun playlistAt(serverTime: String, firstSegment: String) = buildString {
        appendLine("#EXT-X-DATERANGE:ID=\"playlist-creation-1\",CLASS=\"timestamp\",X-SERVER-TIME=\"$serverTime\"")
        appendLine("#EXT-X-PROGRAM-DATE-TIME:$firstSegment")
        appendLine("#EXTINF:2.000,live")
        appendLine("https://example.invalid/a.ts")
    }

    @Test
    fun theBoardKeepsTheFirstPlaylistOfEachJoin() {
        LiveTimingBoard.forget("tester")
        LiveTimingBoard.onMasterRequested("Tester")
        LiveTimingBoard.onVariantServed("tester", playlistAt("131.000", "1970-01-01T00:01:40.000Z"), atMonoMs = 1_000)
        LiveTimingBoard.onVariantServed("TESTER", playlistAt("131.000", "1970-01-01T00:01:42.000Z"), atMonoMs = 3_000)
        assertEquals(100_000, LiveTimingBoard.first("tester")!!.timing.segments.first().startMs)
        LiveTimingBoard.onMasterRequested("tester")
        assertNull(LiveTimingBoard.first("tester"))
        LiveTimingBoard.forget("tester")
    }

    @Test
    fun withoutADateHeaderTheClockIsAtLeastTheSessionStamp() {
        LiveTimingBoard.forget("tester")
        // Stamped 131.000 and here at 1_000: the clock is at least 130_000 ahead of monotonic.
        LiveTimingBoard.onVariantServed("tester", playlistAt("131.000", "1970-01-01T00:01:40.000Z"), atMonoMs = 1_000)
        assertEquals(130_000, LiveTimingBoard.serverMinusMonoMs("tester"))
        // The same stamp seen later says nothing new.
        LiveTimingBoard.onVariantServed("tester", playlistAt("131.000", "1970-01-01T00:01:42.000Z"), atMonoMs = 3_000)
        assertEquals(130_000, LiveTimingBoard.serverMinusMonoMs("tester"))
        LiveTimingBoard.forget("tester")
    }

    @Test
    fun dateHeadersNarrowTheClockFromBothSides() {
        LiveTimingBoard.forget("tester")
        // An old session stamp (cached) gives a weak floor: 100_000 - 1_000.
        // Date 131 s at mono 1_000: the clock less monotonic is between 130_000 and 131_150.
        LiveTimingBoard.onVariantServed("tester", playlistAt("100.000", "1970-01-01T00:01:40.000Z"), atMonoMs = 1_000, httpDateMs = 131_000)
        assertEquals(130_575, LiveTimingBoard.serverMinusMonoMs("tester"))
        // Date 133 s at mono 2_400: at least 130_600, so the range is now 130_600..131_150.
        LiveTimingBoard.onVariantServed("tester", playlistAt("100.000", "1970-01-01T00:01:42.000Z"), atMonoMs = 2_400, httpDateMs = 133_000)
        assertEquals(130_875, LiveTimingBoard.serverMinusMonoMs("tester"))
        // Date 135 s at mono 5_150: at most 135_000 + 1_150 - 5_150 = 131_000.
        LiveTimingBoard.onVariantServed("tester", playlistAt("100.000", "1970-01-01T00:01:44.000Z"), atMonoMs = 5_150, httpDateMs = 135_000)
        assertEquals(130_800, LiveTimingBoard.serverMinusMonoMs("tester"))
        LiveTimingBoard.forget("tester")
    }

    @Test
    fun aChannelNobodyPlayedHasNoClock() {
        assertNull(LiveTimingBoard.serverMinusMonoMs("nobody_played_this"))
    }

    @Test
    fun theReadoutIsSecondsWithTwoDecimals() {
        assertEquals("0.00s", formatLiveDelay(0))
        assertEquals("12.34s", formatLiveDelay(12_340))
        assertEquals("12.35s", formatLiveDelay(12_345))
        assertEquals("9.05s", formatLiveDelay(9_049))
        assertEquals("0.00s", formatLiveDelay(-50))
    }
}
