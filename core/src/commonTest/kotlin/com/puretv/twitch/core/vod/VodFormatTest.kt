package com.puretv.twitch.core.vod

import kotlin.test.Test
import kotlin.test.assertEquals

class VodFormatTest {
    @Test fun shortTimecode() = assertEquals("4:05", VodFormat.timecode(245_000))
    @Test fun longTimecode() = assertEquals("3:08:03", VodFormat.timecode(11_283_000))
    @Test fun negativeIsZero() = assertEquals("0:00", VodFormat.timecode(-5))

    @Test fun fillsPercentBraceTemplate() = assertEquals(
        "https://x/thumb-320x180.jpg",
        VodFormat.thumbnail("https://x/thumb-%{width}x%{height}.jpg"),
    )

    @Test fun ages() {
        val day = 24L * 60 * 60 * 1000
        val now = 1_000 * day
        assertEquals("Today", VodFormat.age(now - 3_600_000, now))
        assertEquals("Yesterday", VodFormat.age(now - day - 1, now))
        assertEquals("5 days ago", VodFormat.age(now - 5 * day, now))
        assertEquals("3 weeks ago", VodFormat.age(now - 21 * day, now))
        assertEquals("4 months ago", VodFormat.age(now - 125 * day, now))
        assertEquals("1 year ago", VodFormat.age(now - 400 * day, now))
        assertEquals("Today", VodFormat.age(now + day, now))
    }
}
