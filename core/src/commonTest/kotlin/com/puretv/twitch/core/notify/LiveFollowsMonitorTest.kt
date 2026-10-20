package com.puretv.twitch.core.notify

import com.puretv.twitch.core.model.AppSettings
import com.puretv.twitch.core.model.StreamInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LiveFollowsMonitorTest {
    private fun s(login: String, id: String) = StreamInfo(id = id, userId = "u-$login", userLogin = login, userName = login)

    private class Harness(scope: TestScope, var user: String? = "me") {
        var live: List<StreamInfo> = emptyList()
        var fail = false
        var fetchDelayMs = 0L
        var fetches = 0
        val announced = mutableListOf<List<StreamInfo>>()
        var settings = AppSettings()
        val store = InMemoryLiveNotifyStateStore()
        val monitor = LiveFollowsMonitor(
            fetchLive = {
                fetches++
                if (fetchDelayMs > 0) delay(fetchDelayMs)
                if (fail) error("network down")
                live
            },
            store = store,
            settings = { settings },
            userId = { user },
            clock = { scope.testScheduler.currentTime },
            onGoLive = { announced += it },
        )
    }

    @Test fun successfulPollPublishesLiveNowAndAnnouncesNewStreams() = runTest {
        val h = Harness(this)
        h.live = listOf(s("a", "1"))
        h.monitor.pollNow()
        assertEquals(listOf("a"), h.monitor.liveNow.value.map { it.userLogin })
        assertTrue(h.announced.isEmpty(), "the first poll only seeds")

        advanceTimeBy(LiveFollowsMonitor.SHARE_WINDOW_MS + 1)
        h.live = listOf(s("a", "1"), s("b", "2"))
        h.monitor.pollNow()
        assertEquals(listOf(listOf("b")), h.announced.map { batch -> batch.map { it.userLogin } })
        assertEquals(2, h.monitor.liveNow.value.size)
    }

    @Test fun failedFetchKeepsLiveNowAndNeverSaves() = runTest {
        val h = Harness(this)
        h.live = listOf(s("a", "1"))
        h.monitor.pollNow()
        val saves = h.store.saves

        advanceTimeBy(LiveFollowsMonitor.SHARE_WINDOW_MS + 1)
        h.fail = true
        h.monitor.pollNow()
        assertEquals(listOf("a"), h.monitor.liveNow.value.map { it.userLogin })
        assertEquals(saves, h.store.saves)
        assertFailsWith<IllegalStateException> { h.monitor.followedLive("me", maxAgeMs = 0) }
        assertEquals(saves, h.store.saves)
    }

    @Test fun signedOutNeverFetches() = runTest {
        val h = Harness(this, user = null)
        h.monitor.pollNow()
        assertEquals(0, h.fetches)
        assertTrue(h.monitor.liveNow.value.isEmpty())
    }

    @Test fun signingOutClearsLiveNow() = runTest {
        val h = Harness(this)
        h.live = listOf(s("a", "1"))
        h.monitor.pollNow()
        h.user = null
        h.monitor.pollNow()
        assertTrue(h.monitor.liveNow.value.isEmpty())
    }

    @Test fun concurrentPollsShareOneFetch() = runTest {
        val h = Harness(this)
        h.fetchDelayMs = 500
        h.live = listOf(s("a", "1"))
        listOf(async { h.monitor.pollNow() }, async { h.monitor.pollNow() }).awaitAll()
        assertEquals(1, h.fetches)
    }

    @Test fun recentAnswerIsSharedThenRefreshed() = runTest {
        val h = Harness(this)
        h.live = listOf(s("a", "1"))
        h.monitor.followedLive("me")
        advanceTimeBy(10_000)
        assertEquals(listOf("a"), h.monitor.followedLive("me").map { it.userLogin })
        assertEquals(1, h.fetches, "an answer seconds old is reused")
        h.monitor.followedLive("someoneElse")
        assertEquals(2, h.fetches, "never reused across accounts")
        advanceTimeBy(LiveFollowsMonitor.SHARE_WINDOW_MS + 1)
        h.monitor.followedLive("someoneElse")
        assertEquals(3, h.fetches)
    }

    @Test fun anotherScreensFetchStillAnnounces() = runTest {
        // Home or the followed rail asking for live channels runs the detector too, so a
        // go-live seen there is announced instead of silently marked as seen.
        val h = Harness(this)
        h.monitor.followedLive("me")
        advanceTimeBy(LiveFollowsMonitor.SHARE_WINDOW_MS + 1)
        h.live = listOf(s("b", "2"))
        h.monitor.followedLive("me")
        assertEquals(listOf(listOf("b")), h.announced.map { batch -> batch.map { it.userLogin } })
    }

    @Test fun alertsOffStillTracksButNeverAnnounces() = runTest {
        val h = Harness(this)
        h.settings = AppSettings(liveNotifications = false)
        h.monitor.pollNow()
        advanceTimeBy(LiveFollowsMonitor.SHARE_WINDOW_MS + 1)
        h.live = listOf(s("b", "2"))
        h.monitor.pollNow()
        assertTrue(h.announced.isEmpty())
        h.settings = AppSettings(liveNotifications = true)
        advanceTimeBy(LiveFollowsMonitor.SHARE_WINDOW_MS + 1)
        h.monitor.pollNow()
        assertTrue(h.announced.isEmpty(), "a stream seen while alerts were off is not announced late")
    }

    @Test fun pollEveryWaitsForTheIntervalSinceTheLastFetchByAnyone() = runTest {
        val h = Harness(this)
        val job = launch { h.monitor.pollEvery(90_000) }
        runCurrent()
        assertEquals(1, h.fetches, "polls right away")
        advanceTimeBy(60_000)
        h.monitor.followedLive("me", maxAgeMs = 0) // the rail refreshing at 60 s
        assertEquals(2, h.fetches)
        advanceTimeBy(89_000)
        runCurrent()
        assertEquals(2, h.fetches, "the timer counts from the rail's fetch, not its own")
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, h.fetches)
        job.cancel()
    }

    @Test fun alertsSwitchedOffAndBackOnWithNoPollBetweenSeedSilently() = runTest {
        // Off from the tray for hours with the window hidden: nothing polled meanwhile,
        // so what is live when they come back on is not news.
        val h = Harness(this)
        h.live = listOf(s("a", "1"))
        h.monitor.pollNow()
        h.monitor.alertsStopped()
        advanceTimeBy(60 * 60_000L)
        h.live = listOf(s("a", "1"), s("b", "2"))
        h.monitor.pollNow()
        assertTrue(h.announced.isEmpty(), "the first poll after alerts resume only seeds")

        advanceTimeBy(LiveFollowsMonitor.SHARE_WINDOW_MS + 1)
        h.live = listOf(s("a", "1"), s("b", "2"), s("c", "3"))
        h.monitor.pollNow()
        assertEquals(listOf(listOf("c")), h.announced.map { batch -> batch.map { it.userLogin } })
    }

    @Test fun theSameAccountSigningBackInSeedsSilently() = runTest {
        val h = Harness(this)
        h.live = listOf(s("a", "1"))
        h.monitor.pollNow()
        h.user = null
        h.monitor.alertsStopped()
        advanceTimeBy(30 * 60_000L)
        h.user = "me"
        h.live = listOf(s("b", "2"))
        h.monitor.pollNow()
        assertTrue(h.announced.isEmpty())
    }

    @Test fun thePollLoopRestartingWhileAlertsStayOnStillAnnounces() = runTest {
        // The phone and TV restart the loop each time the app comes back on screen; a
        // go-live from while it was away must still be announced.
        val h = Harness(this)
        val first = launch { h.monitor.pollEvery(90_000) }
        runCurrent()
        first.cancel()
        advanceTimeBy(10 * 60_000L)
        h.live = listOf(s("b", "2"))
        val second = launch { h.monitor.pollEvery(90_000) }
        runCurrent()
        assertEquals(listOf(listOf("b")), h.announced.map { batch -> batch.map { it.userLogin } })
        second.cancel()
    }

    @Test fun aPollCancelledWhileLoadingTheStateDoesNotReseed() = runTest {
        val store = SlowStore(GoLiveDetector.evaluate(LiveNotifyState(), "me", listOf(s("a", "1")), 0, emptySet(), true).nextState)
        val announced = mutableListOf<String>()
        val monitor = LiveFollowsMonitor(
            fetchLive = { listOf(s("a", "1"), s("b", "2")) },
            store = store,
            settings = { AppSettings() },
            userId = { "me" },
            clock = { testScheduler.currentTime },
            onGoLive = { batch -> announced += batch.map { it.userLogin } },
        )
        store.loadDelayMs = 1_000
        val poll = launch { monitor.pollNow() }
        runCurrent()
        poll.cancel()
        runCurrent()
        assertTrue(announced.isEmpty())

        store.loadDelayMs = 0
        advanceTimeBy(LiveFollowsMonitor.SHARE_WINDOW_MS + 1)
        monitor.pollNow()
        assertEquals(listOf("b"), announced, "the cancelled poll must not have recorded b as already seen")
    }

    @Test fun aPollCancelledWhileSavingStillAnnounces() = runTest {
        val store = SlowStore(GoLiveDetector.evaluate(LiveNotifyState(), "me", listOf(s("a", "1")), 0, emptySet(), true).nextState)
        val announced = mutableListOf<String>()
        val monitor = LiveFollowsMonitor(
            fetchLive = { listOf(s("a", "1"), s("b", "2")) },
            store = store,
            settings = { AppSettings() },
            userId = { "me" },
            clock = { testScheduler.currentTime },
            onGoLive = { batch -> announced += batch.map { it.userLogin } },
        )
        store.saveDelayMs = 1_000
        val poll = launch { monitor.pollNow() }
        runCurrent()
        poll.cancel()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf("b"), announced, "b is saved as announced, so it has to be announced")
        assertTrue(store.saved?.seen?.containsKey("b") == true)
    }

    private class SlowStore(private var state: LiveNotifyState) : LiveNotifyStateStore {
        var loadDelayMs = 0L
        var saveDelayMs = 0L
        var saved: LiveNotifyState? = null

        override suspend fun load(): LiveNotifyState {
            if (loadDelayMs > 0) delay(loadDelayMs)
            return state
        }

        override suspend fun save(state: LiveNotifyState) {
            if (saveDelayMs > 0) delay(saveDelayMs)
            this.state = state
            saved = state
        }
    }

    @Test fun aThrowingNotifierDoesNotBreakPolling() = runTest {
        var calls = 0
        val store = InMemoryLiveNotifyStateStore()
        var live = emptyList<StreamInfo>()
        val monitor = LiveFollowsMonitor(
            fetchLive = { live },
            store = store,
            settings = { AppSettings() },
            userId = { "me" },
            clock = { testScheduler.currentTime },
            onGoLive = { calls++; error("toast failed") },
        )
        monitor.pollNow()
        advanceTimeBy(LiveFollowsMonitor.SHARE_WINDOW_MS + 1)
        live = listOf(s("b", "2"))
        monitor.pollNow()
        assertEquals(1, calls)
        assertEquals(1, monitor.liveNow.value.size)
    }
}
