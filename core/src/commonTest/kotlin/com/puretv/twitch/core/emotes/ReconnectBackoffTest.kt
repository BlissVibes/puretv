package com.puretv.twitch.core.emotes

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReconnectBackoffTest {

    @Test
    fun aSocketThatClosesWithoutAFrameKeepsBackingOff() {
        val backoff = ReconnectBackoff(initialMs = 1_000, maxMs = 8_000)
        val delays = List(5) { backoff.nextDelayMs() }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), delays)
    }

    @Test
    fun aFrameFromTheServerResetsTheBackoff() {
        val backoff = ReconnectBackoff(initialMs = 1_000, maxMs = 8_000)
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        backoff.onHealthy()
        assertEquals(1_000L, backoff.nextDelayMs())
        assertEquals(2_000L, backoff.nextDelayMs())
    }

    @Test
    fun jitterIsAddedOnTop() {
        assertEquals(1_250L, ReconnectBackoff().nextDelayMs(jitterMs = 250))
    }

    @Test
    fun aSocketThatStaysUpALongTimeCountsAsHealthy() {
        val backoff = ReconnectBackoff(initialMs = 1_000, maxMs = 8_000, healthyAfterMs = 10_000)
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        backoff.onSessionEnded(livedMs = 2_000)
        assertEquals(4_000L, backoff.nextDelayMs())
        backoff.onSessionEnded(livedMs = 15_000)
        assertEquals(1_000L, backoff.nextDelayMs())
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun aMissingUserOrANonJsonBodyMeansNoSevenTvSet() {
        assertNull(parseEmoteSetLookup(404, """{"error":"not found"}""", json))
        assertNull(parseEmoteSetLookup(200, "<html>oops</html>", json))
    }

    @Test
    fun theLookupReadsTheSetIdAndRetriesOnServerErrors() {
        assertEquals("abc", parseEmoteSetLookup(200, """{"emote_set":{"id":"abc"}}""", json))
        assertFailsWith<IllegalStateException> { parseEmoteSetLookup(503, "", json) }
    }
}
