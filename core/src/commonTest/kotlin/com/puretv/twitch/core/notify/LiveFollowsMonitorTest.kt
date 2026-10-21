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

    private class Harness(scope: TestScope, channels: Set<String> = setOf("a", "b", "c")) {
        var live: List<StreamInfo> = emptyList()
        var fail = false
        var fetchDelayMs = 0L
        var fetches = 0
        var feedFetches = 0
        val requested = mutableListOf<List<String>>()
        val announced = mutableListOf<List<StreamInfo>>()
        var settings = AppSettings(notifyChannels = channels)
        val store = InMemoryLiveNotifyStateStore()
        val monitor = LiveFollowsMonitor(
            fetchLive = { feedFetches++; live },
            fetchWatched = { logins ->
                fetches++
                requested += logins
                if (fetchDelayMs > 0) delay(fetchDelayMs)
                if (fail) error("network down")
                live.filter { it.userLogin in logins }
            },
            store = store,
            settings = { settings },
            clock = { scope.testScheduler.currentTime },
            onGoLive = { announced += it },
        )
    }

    private fun Harness.announcedLogins() = announced.map { batch -> batch.map { it.userLogin } }

    @Test fun firstPollSeedsThenNewStreamsAreAnnounced() = runTest {
        val h = Harness(this)
        h.live = listOf(s("a", "1"))
        h.monitor.pollNow()
        assertTrue(h.announced.isEmpty(), "the first poll only seeds")

        advanceTimeBy(LiveFollowsMonitor.SHARE_WINDOW_MS + 1)
        h.live = listOf(s("a", "1"), s("b", "2"))
        h.monitor.pollNow()
        assertEquals(listOf(listOf("b")), h.announcedLogins())
    }

    @Test fun oneBatchedRequestCoversEveryOptedInChannel() = runTest {
        val h = Harness(this, channels = setOf("Alpha", " beta ", "alpha"))
        h.monitor.pollNow()
        assertEquals(1, h.fetches)
        assertEquals(listOf("alpha", "beta"), h.requested.single().sorted())
    }

    @Test fun nothingOptedInMakesNoRequests() = runTest {
        val h = Harness(this, channels = emptySet())
        h.live = listOf(s("a", "1"))
        h.monitor.pollNow()
        val loop = launch { h.monitor.pollEvery(90_000) }
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        loop.cancel()
        assertEquals(0, h.fetches)
        assertEquals(0, h.feedFetches)
        assertTrue(h.announced.isEmpty())
    }

    @Test fun onlyOptedInChannelsAreEverAnnounced() = runTest {
        val h = Harness(this, channels = setOf("b"))
        h.monitor.pollNow()
        advanceTimeBy(LiveFollowsMonitor.SHARE_WINDOW_MS + 1)
        h.live = listOf(s("a", "1"), s("b", "2"))
        h.monitor.pollNow()
        assertEquals(listOf(listOf("b")), h.announcedLogins())
        assertEquals(listOf("b"), h.requested.last())
    }

    @Test fun failedFetchNeverSavesOrAnnounces() = runTest {
        val h = Harness(this)
        h.live = listOf(s("a", "1"))
        h.monitor.pollNow()
        val saves = h.store.saves

        advanceTimeBy(LiveFollowsMonitor.SHARE_WINDOW_MS + 1)
        h.fail = true
        h.live = listOf(s("a", "1"), s("b", "2"))
        h.monitor.pollNow()
        assertEquals(saves, h.store.saves)
        assertTrue(h.announced.isEmpty())
    }

    @Test fun aFailedFirstPollForANewBellIsRetriedAtOnce() = runTest {
        val h = Harness(this, channels = setOf("a"))
        h.fail = true
        h.monitor.pollNow()
        h.fail = false
        h.monitor.pollNow()
        assertEquals(2, h.fetches, "a failed poll does not start the share window")
        val loop = launch { h.monitor.pollEvery(90_000) }
        runCurrent()
        assertEquals(2, h.fetches, "and a good one does")
        loop.cancel()
    }

    @Test fun concurrentPollsShareOneFetch() = runTest {
        val h = Harness(this)
        h.fetchDelayMs = 500
        h.live = listOf(s("a", "1"))
        listOf(async { h.monitor.pollNow() }, async { h.monitor.pollNow() }).awaitAll()
        assertEquals(1, h.fetches)
    }

    @Test fun theFollowedFeedIsSharedAndNeverAnnounces() = runTest {
        val h = Harness(this)
        h.live = listOf(s("a", "1"))
        h.monitor.followedLive("me")
        advanceTimeBy(10_000)
        assertEquals(listOf("a"), h.monitor.followedLive("me").map { it.userLogin })
        assertEquals(1, h.feedFetches, "an answer seconds old is reused")
        h.monitor.followedLive("someoneElse")
        assertEquals(2, h.feedFetches, "never reused across accounts")
        advanceTimeBy(LiveFollowsMonitor.SHARE_WINDOW_MS + 1)
        h.live = listOf(s("a", "1"), s("b", "2"))
        h.monitor.followedLive("someoneElse")
        assertEquals(3, h.feedFetches)
        assertEquals(listOf("a", "b"), h.monitor.liveNow.value.map { it.userLogin })
        assertEquals(0, h.fetches, "the feed does not touch the alert poll")
        assertTrue(h.announced.isEmpty())
    }

    @Test fun failedFeedFetchThrowsAndKeepsLiveNow() = runTest {
        var fail = false
        val monitor = LiveFollowsMonitor(
            fetchLive = { if (fail) error("down") else listOf(s("a", "1")) },
            fetchWatched = { emptyList() },
            store = InMemoryLiveNotifyStateStore(),
            settings = { AppSettings() },
            clock = { testScheduler.currentTime },
            onGoLive = {},
        )
        monitor.followedLive("me")
        fail = true
        assertFailsWith<IllegalStateException> { monitor.followedLive("me", maxAgeMs = 0) }
        assertEquals(listOf("a"), monitor.liveNow.value.map { it.userLogin })
    }

    @Test fun alertsOffMakesNoRequestAndNeverAnnounces() = runTest {
        val h = Harness(this)
        h.settings = h.settings.copy(liveNotifications = false)
        h.monitor.pollNow()
        assertEquals(0, h.fetches)
        h.settings = h.settings.copy(liveNotifications = true)
        h.live = listOf(s("b", "2"))
        h.monitor.pollNow()
        assertTrue(h.announced.isEmpty(), "a stream already live when alerts come back is not news")
    }

    @Test fun aChannelOptedInWhileLiveIsNotAnnouncedButItsNextBroadcastIs() = runTest {
        val h = Harness(this, channels = setOf("a"))
        h.monitor.pollNow()
        advanceTimeBy(LiveFollowsMonitor.SHARE_WINDOW_MS + 1)
        h.live = listOf(s("b", "2"))
        h.settings = h.settings.copy(notifyChannels = setOf("a", "b"))
        h.monitor.pollNow()
        assertTrue(h.announced.isEmpty(), "tapping the bell on a live channel does not announce it")

        advanceTimeBy(LiveFollowsMonitor.SHARE_WINDOW_MS + 1)
        h.live = listOf(s("b", "3"))
        h.monitor.pollNow()
        assertEquals(listOf(listOf("b")), h.announcedLogins(), "a new broadcast is announced")
    }

    @Test fun anOptedInChannelThatWasOfflineIsAnnouncedWhenItGoesLive() = runTest {
        val h = Harness(this, channels = setOf("a"))
        h.monitor.pollNow()
        advanceTimeBy(LiveFollowsMonitor.SHARE_WINDOW_MS + 1)
        h.settings = h.settings.copy(notifyChannels = setOf("a", "b"))
        h.monitor.pollNow()
        advanceTimeBy(LiveFollowsMonitor.SHARE_WINDOW_MS + 1)
        h.live = listOf(s("b", "2"))
        h.monitor.pollNow()
        assertEquals(listOf(listOf("b")), h.announcedLogins())
    }

    @Test fun pollEveryPollsAtOnceWhenTheOptInListChanges() = runTest {
        val h = Harness(this, channels = setOf("a"))
        val job = launch { h.monitor.pollEvery(90_000) }
        runCurrent()
        assertEquals(1, h.fetches, "polls right away")
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, h.fetches, "waits out the interval")
        job.cancel()
        h.settings = h.settings.copy(notifyChannels = setOf("a", "b"))
        val restarted = launch { h.monitor.pollEvery(90_000) }
        runCurrent()
        assertEquals(2, h.fetches, "a changed list does not wait")
        assertEquals(listOf("a", "b"), h.requested.last().sorted())
        restarted.cancel()
    }

    @Test fun removingEveryChannelResetsSoALaterOptInSeedsAgain() = runTest {
        val h = Harness(this, channels = setOf("a"))
        h.live = listOf(s("a", "1"))
        h.monitor.pollNow()
        h.settings = h.settings.copy(notifyChannels = emptySet())
        h.monitor.pollNow()
        advanceTimeBy(60 * 60_000L)
        h.settings = h.settings.copy(notifyChannels = setOf("a"))
        h.live = listOf(s("a", "2"))
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
        assertEquals(listOf(listOf("b")), h.announcedLogins())
        second.cancel()
    }

    private fun seededState() =
        GoLiveDetector.evaluate(LiveNotifyState(), "", listOf(s("a", "1")), 0, enabled = true, watched = setOf("a", "b")).nextState

    @Test fun aPollCancelledWhileLoadingTheStateDoesNotReseed() = runTest {
        val store = SlowStore(seededState())
        val announced = mutableListOf<String>()
        val monitor = LiveFollowsMonitor(
            fetchLive = { emptyList() },
            fetchWatched = { listOf(s("a", "1"), s("b", "2")) },
            store = store,
            settings = { AppSettings(notifyChannels = setOf("a", "b")) },
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
        val store = SlowStore(seededState())
        val announced = mutableListOf<String>()
        val monitor = LiveFollowsMonitor(
            fetchLive = { emptyList() },
            fetchWatched = { listOf(s("a", "1"), s("b", "2")) },
            store = store,
            settings = { AppSettings(notifyChannels = setOf("a", "b")) },
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
            fetchLive = { emptyList() },
            fetchWatched = { live },
            store = store,
            settings = { AppSettings(notifyChannels = setOf("b")) },
            clock = { testScheduler.currentTime },
            onGoLive = { calls++; error("toast failed") },
        )
        monitor.pollNow()
        advanceTimeBy(LiveFollowsMonitor.SHARE_WINDOW_MS + 1)
        live = listOf(s("b", "2"))
        monitor.pollNow()
        assertEquals(1, calls)
    }
}
