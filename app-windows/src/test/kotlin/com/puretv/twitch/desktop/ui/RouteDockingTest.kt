package com.puretv.twitch.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The stream that stays in the corner while browsing (see RouteHolder). */
class RouteDockingTest {
    private val vod = VodLaunch(vodId = "1", channelLogin = "a", title = "t", thumbnailUrl = "")

    @Test fun leavingAStreamForABrowsingScreenDocksIt() {
        val nav = RouteHolder(Route.Stream("hasanabi"))
        nav.current = Route.Top
        assertEquals("hasanabi", nav.dockedLogin)
        // It stays docked through more browsing.
        nav.current = Route.Channel("other")
        nav.current = Route.Category("1", "Games")
        assertEquals("hasanabi", nav.dockedLogin)
    }

    @Test fun goingBackToTheStreamUndocksIt() {
        val nav = RouteHolder(Route.Stream("hasanabi"))
        nav.current = Route.Top
        nav.current = Route.Stream("hasanabi")
        assertNull(nav.dockedLogin)
    }

    @Test fun anyOtherPlayerTakesOverFromTheDockedStream() {
        for (player in listOf(Route.Stream("other"), Route.Multi("other"), Route.Vod(vod))) {
            val nav = RouteHolder(Route.Stream("hasanabi"))
            nav.current = Route.Top
            nav.current = player
            assertNull(nav.dockedLogin, "after $player")
        }
    }

    @Test fun leavingAPastVideoOrMultiStreamDocksNothing() {
        assertNull(RouteDocking.next(Route.Vod(vod), Route.Top, null))
        assertNull(RouteDocking.next(Route.Multi("a"), Route.Top, null))
    }

    @Test fun closingTheDockedStreamStaysClosed() {
        val nav = RouteHolder(Route.Stream("hasanabi"))
        nav.current = Route.Top
        nav.dockedLogin = null
        nav.current = Route.Channel("x")
        assertNull(nav.dockedLogin)
    }
}
