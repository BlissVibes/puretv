package com.puretv.twitch.core.emotes

import kotlin.test.Test
import kotlin.test.assertEquals

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
        backoff.onFrame()
        assertEquals(1_000L, backoff.nextDelayMs())
        assertEquals(2_000L, backoff.nextDelayMs())
    }

    @Test
    fun jitterIsAddedOnTop() {
        assertEquals(1_250L, ReconnectBackoff().nextDelayMs(jitterMs = 250))
    }
}
