package com.puretv.twitch.core.notify

import com.puretv.twitch.core.model.StreamInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class GoLiveAlertsTest {
    private fun s(login: String, title: String = "") =
        StreamInfo(id = "id-$login", userId = "u-$login", userLogin = login, userName = login.replaceFirstChar { it.uppercase() }, title = title)

    @Test fun watchedChannelIsLeftOutIgnoringCase() {
        val streams = listOf(s("alice"), s("bob"))
        assertEquals(listOf("bob"), GoLiveAlerts.exceptWatching(streams, "ALICE").map { it.userLogin })
        assertEquals(streams, GoLiveAlerts.exceptWatching(streams, null))
        assertEquals(streams, GoLiveAlerts.exceptWatching(streams, " "))
    }

    @Test fun theBellTogglesOneChannelIgnoringCase() {
        val on = GoLiveAlerts.toggled(emptySet(), " Alice ")
        assertEquals(setOf("alice"), on)
        assertEquals(true, GoLiveAlerts.isOn(on, "ALICE"))
        assertEquals(false, GoLiveAlerts.isOn(on, "bob"))
        assertEquals(setOf("alice", "bob"), GoLiveAlerts.toggled(on, "bob"))
        assertEquals(emptySet(), GoLiveAlerts.toggled(on, "alice"))
        assertEquals(on, GoLiveAlerts.toggled(on, " "))
        assertEquals(false, GoLiveAlerts.isOn(on, ""))
    }

    @Test fun turningABellOnAlsoTurnsTheGlobalToggleOn() {
        val off = com.puretv.twitch.core.model.AppSettings(liveNotifications = false)
        val on = GoLiveAlerts.bellTapped(off, "Alice")
        assertEquals(setOf("alice"), on.notifyChannels)
        assertEquals(true, on.liveNotifications)
        val removed = GoLiveAlerts.bellTapped(on.copy(liveNotifications = false), "alice")
        assertEquals(emptySet(), removed.notifyChannels)
        assertEquals(false, removed.liveNotifications, "removing a bell leaves the toggle alone")
    }

    @Test fun bellConfirmationNamesTheChannel() {
        assertEquals("You'll be told when Alice goes live", GoLiveText.bellConfirmation("Alice", true))
        assertEquals("Alerts off for Alice", GoLiveText.bellConfirmation("Alice", false))
    }
}
