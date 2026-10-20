package com.puretv.twitch.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayTicketTest {
    @Test fun theOnlyScreenStopsThePlayerWhenItGoesAway() {
        val tickets = PlayTicket()
        val first = tickets.issue()
        var stops = 0
        assertTrue(tickets.stopIfCurrent(first) { stops++ })
        assertEquals(1, stops)
    }

    @Test fun anOlderScreenCannotStopANewerOnesStream() {
        val tickets = PlayTicket()
        val old = tickets.issue()
        // The next screen is built (and asks for its stream) before the old one is cleaned up.
        val next = tickets.issue()
        var stops = 0
        assertFalse(tickets.stopIfCurrent(old) { stops++ })
        assertEquals(0, stops)
        assertTrue(tickets.stopIfCurrent(next) { stops++ })
        assertEquals(1, stops)
    }

    @Test fun aLeaveWithNoSuccessorStillStops() {
        val tickets = PlayTicket()
        tickets.issue()
        val last = tickets.issue()
        var stopped = false
        tickets.stopIfCurrent(last) { stopped = true }
        assertTrue(stopped)
    }
}
