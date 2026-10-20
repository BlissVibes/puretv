package com.puretv.twitch.desktop.notify

import com.puretv.twitch.core.model.StreamInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopLiveNotifierTest {
    private fun s(login: String) = StreamInfo(id = login, userId = login, userLogin = login, userName = login)

    private class Tray(var present: Boolean = true) {
        val shown = mutableListOf<Pair<String, String>>()
        val deliver: (String, String) -> Boolean = { title, body -> present.also { if (it) shown += title to body } }
    }

    @Test fun aClickSoonAfterASingleAlertOpensThatStreamOnce() {
        var now = 0L
        val notifier = DesktopLiveNotifier(Tray().deliver, clock = { now })
        notifier.show(listOf(s("alice")))
        now += 10_000
        assertEquals("alice", notifier.consumeRecentTarget())
        assertNull(notifier.consumeRecentTarget(), "a later double-click on the tray icon just shows the window")
    }

    @Test fun aDoubleClickLongAfterTheToastJustShowsTheWindow() {
        var now = 0L
        val notifier = DesktopLiveNotifier(Tray().deliver, clock = { now })
        notifier.show(listOf(s("alice")))
        now += DesktopLiveNotifier.TARGET_TTL_MS + 1
        assertNull(notifier.consumeRecentTarget())
    }

    @Test fun aGroupedAlertHasNoStreamToOpen() {
        val notifier = DesktopLiveNotifier(Tray().deliver)
        notifier.show(listOf(s("alice"), s("bob")))
        assertNull(notifier.consumeRecentTarget())
    }

    @Test fun anAlertBeforeTheTrayExistsIsShownOnceItDoes() {
        var now = 0L
        val tray = Tray(present = false)
        val notifier = DesktopLiveNotifier(tray.deliver, clock = { now })
        notifier.show(listOf(s("alice")))
        notifier.show(listOf(s("bob")))
        assertTrue(tray.shown.isEmpty())
        assertNull(notifier.consumeRecentTarget(), "nothing was shown, so nothing to open")

        now += 5_000
        tray.present = true
        notifier.flushPending()
        assertEquals(1, tray.shown.size, "both go-lives arrive as one toast")
        notifier.flushPending()
        assertEquals(1, tray.shown.size, "and only once")
    }

    @Test fun anAlertThatCouldNotBeShownForAgesIsDropped() {
        var now = 0L
        val tray = Tray(present = false)
        val notifier = DesktopLiveNotifier(tray.deliver, clock = { now })
        notifier.show(listOf(s("alice")))
        now += DesktopLiveNotifier.PENDING_TTL_MS + 1
        tray.present = true
        notifier.flushPending()
        assertTrue(tray.shown.isEmpty())
    }

    @Test fun theChannelBeingWatchedIsNotToasted() {
        val tray = Tray()
        val notifier = DesktopLiveNotifier(tray.deliver)
        notifier.watchingLogin = "Alice"
        notifier.show(listOf(s("alice")))
        assertTrue(tray.shown.isEmpty())
        notifier.show(listOf(s("alice"), s("bob")))
        assertEquals(1, tray.shown.size)
        assertEquals("bob", notifier.consumeRecentTarget(), "the toast is about bob alone")
    }
}
