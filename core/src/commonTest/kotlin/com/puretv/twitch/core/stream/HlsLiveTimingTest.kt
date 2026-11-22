package com.puretv.twitch.core.stream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HlsLiveTimingTest {
    /** A Twitch-shaped playlist of [count] two-second segments starting at 09:11:47.771Z. */
    private fun playlist(count: Int, dateEvery: Int = 1): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-TARGETDURATION:2")
        appendLine(
            "#EXT-X-DATERANGE:ID=\"playlist-creation-1795338736\",CLASS=\"timestamp\"," +
                "START-DATE=\"2026-11-22T09:12:16.794Z\",END-ON-NEXT=YES,X-SERVER-TIME=\"1795338736.79\"",
        )
        for (i in 0 until count) {
            if (i % dateEvery == 0) {
                val s = 47 + 2 * i
                val mm = (11 + s / 60).toString().padStart(2, '0')
                val ss = (s % 60).toString().padStart(2, '0')
                appendLine("#EXT-X-PROGRAM-DATE-TIME:2026-11-22T09:$mm:$ss.771Z")
            }
            appendLine("#EXTINF:2.000,live")
            appendLine("https://example.invalid/v1/segment/$i.ts")
        }
    }

    private val first = 1_795_338_707_771L // 2026-11-22T09:11:47.771Z

    @Test
    fun readsADateAsEpochMilliseconds() {
        assertEquals(0L, HlsLiveTiming.parseInstantMs("1970-01-01T00:00:00Z"))
        assertEquals(951_782_400_000L, HlsLiveTiming.parseInstantMs("2000-02-29T00:00:00.000Z"))
        assertEquals(first, HlsLiveTiming.parseInstantMs("2026-11-22T09:11:47.771Z"))
        assertEquals(first - 771 + 700, HlsLiveTiming.parseInstantMs("2026-11-22T09:11:47.7Z"))
        assertEquals(first, HlsLiveTiming.parseInstantMs("2026-11-22T09:11:47.771999Z"))
    }

    @Test
    fun aDateItCannotReadIsNull() {
        assertNull(HlsLiveTiming.parseInstantMs("yesterday"))
        assertNull(HlsLiveTiming.parseInstantMs("2026-11-22T09:11:47.771+01:00"))
        assertNull(HlsLiveTiming.parseInstantMs("2026-13-22T09:11:47Z"))
    }

    @Test
    fun readsEverySegmentAndTheServerClock() {
        val t = HlsLiveTiming.parse(playlist(14))!!
        assertEquals(14, t.segments.size)
        assertEquals(HlsLiveTiming.Segment(first, 2000), t.segments.first())
        assertEquals(first + 26_000, t.segments.last().startMs)
        assertEquals(1_795_338_736_790L, t.serverTimeMs)
    }

    @Test
    fun aSegmentWithoutADateFollowsTheOneBefore() {
        val t = HlsLiveTiming.parse(playlist(6, dateEvery = 3))!!
        assertEquals(listOf(0L, 2000L, 4000L, 6000L, 8000L, 10_000L), t.segments.map { it.startMs - first })
    }

    @Test
    fun aPlaylistWithoutDatesGivesNothing() {
        assertNull(HlsLiveTiming.parse("#EXTM3U\n#EXTINF:2.000,live\nhttps://example.invalid/a.ts\n"))
        assertNull(HlsLiveTiming.parse(""))
    }

    @Test
    fun vlcStartsWhereItWasMeasuredToStart() {
        val t = HlsLiveTiming.parse(playlist(14))!!
        // VLC 3.0.24, 14 segments: the default 15 s live delay fetched segment 6 first, 6 s segment 11.
        assertEquals(t.segments[5].startMs, t.vlcStartMs(15_000))
        assertEquals(t.segments[10].startMs, t.vlcStartMs(6_000))
    }

    @Test
    fun aDelayLongerThanThePlaylistStartsAtItsFirstSegment() {
        val t = HlsLiveTiming.parse(playlist(4))!!
        assertEquals(first, t.vlcStartMs(15_000))
    }
}
