package com.puretv.twitch.desktop.player

import com.puretv.twitch.core.model.StreamQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QualityMatchTest {
    // The master playlist lines Twitch sent for a 1080p60 channel: Source is "chunked".
    private val source1080 = """#EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",VIDEO="chunked",FRAME-RATE=60.000"""
    private val p720 = """#EXT-X-STREAM-INF:BANDWIDTH=3422999,RESOLUTION=1280x720,CODECS="avc1.4D401F,mp4a.40.2",VIDEO="720p60",FRAME-RATE=60.000"""
    private val p160 = """#EXT-X-STREAM-INF:BANDWIDTH=230000,RESOLUTION=284x160,CODECS="avc1.4D400C,mp4a.40.2",VIDEO="160p30",FRAME-RATE=30.000"""

    @Test fun a1080p60PickFindsTheChunkedSourceByItsHeight() {
        assertTrue(LocalStreamProxy.variantMatches(source1080, StreamQuality.P1080P60))
        assertTrue(LocalStreamProxy.variantMatches(source1080, StreamQuality.SOURCE))
        assertFalse(LocalStreamProxy.variantMatches(p720, StreamQuality.P1080P60))
        assertFalse(LocalStreamProxy.variantMatches(p160, StreamQuality.P1080P60))
    }

    @Test fun namedRenditionsStillMatchByName() {
        assertTrue(LocalStreamProxy.variantMatches(p720, StreamQuality.P720P60))
        assertFalse(LocalStreamProxy.variantMatches(p720, StreamQuality.P480P))
    }

    @Test fun autoAndAudioNeverMatchAHeight() {
        assertFalse(LocalStreamProxy.variantMatches(source1080, StreamQuality.AUTO))
        assertFalse(LocalStreamProxy.variantMatches(p720, StreamQuality.AUDIO_ONLY))
    }

    // The order and names Twitch sent for theburntpeanut: 360p first, top rendition "1080p60", no "chunked".
    private val noChunked = listOf(
        """#EXT-X-STREAM-INF:BANDWIDTH=627900,RESOLUTION=640x360,VIDEO="360p30",FRAME-RATE=30.000""",
        """#EXT-X-STREAM-INF:BANDWIDTH=216299,RESOLUTION=284x160,VIDEO="160p30",FRAME-RATE=30.000""",
        """#EXT-X-STREAM-INF:BANDWIDTH=8042999,RESOLUTION=1920x1080,VIDEO="1080p60",FRAME-RATE=60.000""",
        """#EXT-X-STREAM-INF:BANDWIDTH=3322199,RESOLUTION=1280x720,VIDEO="720p60",FRAME-RATE=60.000""",
        """#EXT-X-STREAM-INF:BANDWIDTH=1327200,RESOLUTION=852x480,VIDEO="480p30",FRAME-RATE=30.000""",
    )

    @Test fun sourceIsTheHighestBandwidthWhenNothingIsNamedChunked() {
        assertEquals(2, LocalStreamProxy.chooseVariant(noChunked, StreamQuality.SOURCE))
    }

    @Test fun sourcePrefersTheChunkedRendition() {
        assertEquals(0, LocalStreamProxy.chooseVariant(listOf(source1080, p720, p160), StreamQuality.SOURCE))
    }

    @Test fun eachHeightFindsItsRendition() {
        assertEquals(2, LocalStreamProxy.chooseVariant(noChunked, StreamQuality.P1080P60))
        assertEquals(3, LocalStreamProxy.chooseVariant(noChunked, StreamQuality.P720P60))
        assertEquals(4, LocalStreamProxy.chooseVariant(noChunked, StreamQuality.P480P))
        assertEquals(0, LocalStreamProxy.chooseVariant(noChunked, StreamQuality.P360P))
    }

    @Test fun aMissingQualityFallsToTheNearestBelowNotTheWholeList() {
        val upTo720 = listOf(p720, p160)
        assertEquals(0, LocalStreamProxy.chooseVariant(upTo720, StreamQuality.P1080P60), "1080p60 on a 720p channel plays 720p")
        assertEquals(1, LocalStreamProxy.chooseVariant(listOf(p720, p160), StreamQuality.P480P), "nothing at 480: 160p is below")
        assertEquals(0, LocalStreamProxy.chooseVariant(listOf(p720), StreamQuality.P360P), "nothing at or below: the lowest above")
    }

    @Test fun autoAndAudioAreLeftToTheCaller() {
        assertNull(LocalStreamProxy.chooseVariant(noChunked, StreamQuality.AUTO))
        assertNull(LocalStreamProxy.chooseVariant(noChunked, StreamQuality.AUDIO_ONLY))
        assertNull(LocalStreamProxy.chooseVariant(emptyList(), StreamQuality.SOURCE))
    }
}
