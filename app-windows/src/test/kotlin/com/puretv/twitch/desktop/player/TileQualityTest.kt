package com.puretv.twitch.desktop.player

import com.puretv.twitch.core.model.StreamQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TileQualityTest {

    @Test fun twoTilesAllowUpTo1080p60() {
        assertEquals(StreamQuality.P1080P60, TileQuality.capFor(2, StreamQuality.AUTO))
        assertEquals(StreamQuality.P1080P60, TileQuality.capFor(2, StreamQuality.SOURCE))
        assertEquals(StreamQuality.P1080P60, TileQuality.capFor(2, StreamQuality.P1080P60))
    }

    @Test fun threeOrFourTilesAllowUpTo720p60() {
        assertEquals(StreamQuality.P720P60, TileQuality.capFor(3, StreamQuality.AUTO))
        assertEquals(StreamQuality.P720P60, TileQuality.capFor(4, StreamQuality.SOURCE))
        assertEquals(StreamQuality.P720P60, TileQuality.capFor(4, StreamQuality.P1080P60))
    }

    @Test fun aLowerPreferenceWinsOverTheCap() {
        assertEquals(StreamQuality.P480P, TileQuality.capFor(2, StreamQuality.P480P))
        assertEquals(StreamQuality.P360P, TileQuality.capFor(4, StreamQuality.P360P))
        assertEquals(StreamQuality.P720P60, TileQuality.capFor(2, StreamQuality.P720P60))
    }

    @Test fun audioOnlyPreferenceDoesNotBlankATile() {
        assertEquals(StreamQuality.P720P60, TileQuality.capFor(3, StreamQuality.AUDIO_ONLY))
    }

    @Test fun capDoesNotDependOnWhichTileIsAudible() {
        // The cap takes the tile count and the preference only: there is nothing to
        // change when the sound moves, so no stream reloads.
        assertEquals(TileQuality.capFor(3, StreamQuality.AUTO), TileQuality.capFor(3, StreamQuality.AUTO))
    }

    @Test fun maxHeights() {
        assertEquals(1080, TileQuality.maxHeight(StreamQuality.P1080P60))
        assertEquals(720, TileQuality.maxHeight(StreamQuality.P720P60))
        assertEquals(480, TileQuality.maxHeight(StreamQuality.P480P))
        assertEquals(360, TileQuality.maxHeight(StreamQuality.P360P))
    }

    private fun master(vararg inf: Pair<String, String>): String = buildString {
        append("#EXTM3U\n#EXT-X-TWITCH-INFO:NODE=\"x\"\n")
        inf.forEach { (attrs, url) ->
            append("#EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID=\"g\",NAME=\"n\"\n")
            append("#EXT-X-STREAM-INF:$attrs\n$url\n")
        }
    }

    private fun urls(m: String) = m.lines().filter { it.startsWith("http") }

    private val source = "BANDWIDTH=8000000,RESOLUTION=1920x1080,FRAME-RATE=60.000" to "https://x.ttvnw.net/source"
    private val p720 = "BANDWIDTH=3000000,RESOLUTION=1280x720,FRAME-RATE=60.000" to "https://x.ttvnw.net/720p60"
    private val p720a = "BANDWIDTH=2000000,RESOLUTION=1280x720,FRAME-RATE=30.000" to "https://x.ttvnw.net/720p30"
    private val p480 = "BANDWIDTH=1000000,RESOLUTION=852x480,FRAME-RATE=30.000" to "https://x.ttvnw.net/480p"
    private val audio = "BANDWIDTH=160000,CODECS=\"mp4a.40.2\"" to "https://x.ttvnw.net/audio_only"

    @Test fun picksTheTallestRenditionAtOrBelowTheCap() {
        val m = master(source, p720, p720a, p480, audio)
        assertEquals(listOf("https://x.ttvnw.net/720p60"), urls(TileQuality.capMaster(m, 720)))
        assertEquals(listOf("https://x.ttvnw.net/source"), urls(TileQuality.capMaster(m, 1080)))
        assertEquals(listOf("https://x.ttvnw.net/480p"), urls(TileQuality.capMaster(m, 480)))
    }

    @Test fun prefersTheHigherFrameRateAtTheSameHeight() {
        val m = master(p720a, p720)
        assertEquals(listOf("https://x.ttvnw.net/720p60"), urls(TileQuality.capMaster(m, 720)))
    }

    @Test fun fallsBackToTheSmallestWhenEverythingIsAboveTheCap() {
        val m = master(source, p720)
        assertEquals(listOf("https://x.ttvnw.net/720p60"), urls(TileQuality.capMaster(m, 360)))
    }

    @Test fun neverPicksTheAudioOnlyRendition() {
        val m = master(p480, audio)
        assertEquals(listOf("https://x.ttvnw.net/480p"), urls(TileQuality.capMaster(m, 720)))
    }

    @Test fun keepsHeaderTagsAndDropsTheOtherVariantLines() {
        val result = TileQuality.capMaster(master(source, p720), 720)
        assertTrue(result.startsWith("#EXTM3U"))
        assertTrue(result.contains("#EXT-X-TWITCH-INFO"))
        assertEquals(1, result.lines().count { it.startsWith("#EXT-X-STREAM-INF") })
        assertFalse(result.contains("source"))
    }

    @Test fun leavesAMasterWithNoSizedRenditionsAlone() {
        val m = master(audio)
        assertEquals(m, TileQuality.capMaster(m, 720))
    }
}
