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
}
