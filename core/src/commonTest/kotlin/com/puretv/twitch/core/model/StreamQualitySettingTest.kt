package com.puretv.twitch.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamQualitySettingTest {
    @Test
    fun readsTheLowerCaseNamesEveryAppStores() {
        assertEquals(StreamQuality.AUTO, StreamQuality.fromSetting("auto"))
        assertEquals(StreamQuality.SOURCE, StreamQuality.fromSetting("source"))
        assertEquals(StreamQuality.P1080P60, StreamQuality.fromSetting("p1080p60"))
        assertEquals(StreamQuality.AUDIO_ONLY, StreamQuality.fromSetting("audio_only"))
        assertEquals(StreamQuality.P720P60, StreamQuality.fromSetting(" P720P60 "))
    }

    @Test
    fun anythingUnreadableIsAuto() {
        assertEquals(StreamQuality.AUTO, StreamQuality.fromSetting(""))
        assertEquals(StreamQuality.AUTO, StreamQuality.fromSetting(null))
        assertEquals(StreamQuality.AUTO, StreamQuality.fromSetting("1080p"))
    }

    @Test
    fun aPickInThePlayerSticksExceptAudioOnly() {
        assertEquals("p480p", StreamQuality.preferenceFor(StreamQuality.P480P))
        assertEquals("source", StreamQuality.preferenceFor(StreamQuality.SOURCE))
        assertNull(StreamQuality.preferenceFor(StreamQuality.AUDIO_ONLY))
        // What is saved reads back as the same quality.
        StreamQuality.entries.forEach { q ->
            StreamQuality.preferenceFor(q)?.let { assertEquals(q, StreamQuality.fromSetting(it)) }
        }
    }
}
