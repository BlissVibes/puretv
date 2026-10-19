package com.puretv.twitch.core.stream

import com.puretv.twitch.core.model.StreamQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [HlsMasterParser.forPlayback] and the backup matcher against a master shaped
 * like a live usher answer with `allow_audio_only=true` (captured 2026-10-19,
 * URLs replaced).
 */
class MasterForPlaybackTest {

    @Test
    fun playersNeverSeeTheAudioOnlyRenditionByDefault() {
        val video = HlsMasterParser.forPlayback(MASTER, audioOnly = false)
        assertFalse(video.contains("audio_only"))
        assertEquals(
            listOf(StreamQuality.SOURCE, StreamQuality.P720P60, StreamQuality.P480P),
            HlsMasterParser.parseVariants(video).map { it.quality },
        )
        assertTrue(video.startsWith("#EXTM3U\n#EXT-X-TWITCH-INFO:"))
        // Each kept rendition keeps its EXT-X-MEDIA line.
        assertEquals(3, video.lines().count { it.startsWith("#EXT-X-MEDIA:") })
    }

    @Test
    fun audioOnlyKeepsOnlyTheAudioRendition() {
        val audio = HlsMasterParser.forPlayback(MASTER, audioOnly = true)
        val variants = HlsMasterParser.parseVariants(audio)
        assertEquals(listOf(StreamQuality.AUDIO_ONLY), variants.map { it.quality })
        assertEquals("https://playlist.example/audio.m3u8", variants.single().url)
        assertEquals(listOf("#EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID=\"audio_only\",NAME=\"audio_only\",AUTOSELECT=NO,DEFAULT=NO"),
            audio.lines().filter { it.startsWith("#EXT-X-MEDIA:") })
    }

    @Test
    fun aStreamWithoutAnAudioRenditionKeepsItsVideo() {
        assertEquals(VIDEO_ONLY_MASTER, HlsMasterParser.forPlayback(VIDEO_ONLY_MASTER, audioOnly = true))
        assertEquals(VIDEO_ONLY_MASTER, HlsMasterParser.forPlayback(VIDEO_ONLY_MASTER, audioOnly = false))
    }

    @Test
    fun aVideoBackupNeverMatchesTheAudioRendition() {
        // 284x160 is not in this ladder. By pixel count the audio rendition (no
        // resolution) would be "closest"; it must be the smallest video instead.
        assertEquals(
            "https://playlist.example/480p.m3u8",
            HlsMasterParser.selectVariantUrl(MASTER, targetResolution = "284x160", targetFrameRate = 30.0),
        )
    }

    @Test
    fun anAudioBackupMatchesOnlyTheAudioRendition() {
        assertEquals(
            "https://playlist.example/audio.m3u8",
            HlsMasterParser.selectVariantUrl(MASTER, targetResolution = "", targetFrameRate = 0.0),
        )
        assertEquals(null, HlsMasterParser.selectVariantUrl(VIDEO_ONLY_MASTER, targetResolution = "", targetFrameRate = 0.0))
    }

    private companion object {
        val MASTER = """
            #EXTM3U
            #EXT-X-TWITCH-INFO:NODE="node.example",SERVER-TIME="1792445890.12",TRANSCODEMODE="cbr_v1"
            #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1080p60 (source)",AUTOSELECT=YES,DEFAULT=YES
            #EXT-X-STREAM-INF:BANDWIDTH=8510773,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",VIDEO="chunked",FRAME-RATE=60.000
            https://playlist.example/source.m3u8
            #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="720p60",NAME="720p60",AUTOSELECT=YES,DEFAULT=YES
            #EXT-X-STREAM-INF:BANDWIDTH=3422999,RESOLUTION=1280x720,CODECS="avc1.4D401F,mp4a.40.2",VIDEO="720p60",FRAME-RATE=60.000
            https://playlist.example/720p.m3u8
            #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="480p30",NAME="480p",AUTOSELECT=YES,DEFAULT=YES
            #EXT-X-STREAM-INF:BANDWIDTH=1427999,RESOLUTION=852x480,CODECS="avc1.4D401F,mp4a.40.2",VIDEO="480p30",FRAME-RATE=30.000
            https://playlist.example/480p.m3u8
            #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="audio_only",NAME="audio_only",AUTOSELECT=NO,DEFAULT=NO
            #EXT-X-STREAM-INF:BANDWIDTH=160000,CODECS="mp4a.40.2",VIDEO="audio_only"
            https://playlist.example/audio.m3u8
        """.trimIndent() + "\n"

        val VIDEO_ONLY_MASTER = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1080p60 (source)",AUTOSELECT=YES,DEFAULT=YES
            #EXT-X-STREAM-INF:BANDWIDTH=8510773,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",VIDEO="chunked",FRAME-RATE=60.000
            https://playlist.example/source.m3u8
        """.trimIndent() + "\n"
    }
}
