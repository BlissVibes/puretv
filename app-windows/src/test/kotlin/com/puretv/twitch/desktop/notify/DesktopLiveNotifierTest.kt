package com.puretv.twitch.desktop.notify

import androidx.compose.ui.window.TrayState
import com.puretv.twitch.core.model.StreamInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopLiveNotifierTest {
    private fun s(login: String) = StreamInfo(id = login, userId = login, userLogin = login, userName = login)

    @Test fun aClickSoonAfterASingleAlertOpensThatStreamOnce() {
        var now = 0L
        val notifier = DesktopLiveNotifier(TrayState(), clock = { now })
        notifier.show(listOf(s("alice")))
        now += 30_000
        assertEquals("alice", notifier.consumeRecentTarget())
        assertNull(notifier.consumeRecentTarget(), "a later double-click on the tray icon just shows the window")
    }

    @Test fun anOldOrGroupedAlertHasNoStreamToOpen() {
        var now = 0L
        val notifier = DesktopLiveNotifier(TrayState(), clock = { now })
        notifier.show(listOf(s("alice")))
        now += DesktopLiveNotifier.TARGET_TTL_MS + 1
        assertNull(notifier.consumeRecentTarget())

        notifier.show(listOf(s("alice"), s("bob")))
        assertNull(notifier.consumeRecentTarget())
    }
}
