package com.puretv.twitch.core.notify

import com.puretv.twitch.core.model.AppSettings
import com.puretv.twitch.core.model.StreamInfo
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one place each app asks Twitch two questions: which followed channels are live
 * (`GET /streams/followed`, for Home "Live now" and the Following screens), and which
 * of the channels the viewer turned the bell on for are live, which is where go-live
 * alerts come from.
 *
 * Alerts are per channel. [AppSettings.notifyChannels] is the whole list; nothing else
 * is ever announced, followed or not. [pollNow] asks for exactly those logins in one
 * batched call ([fetchWatched]), so it works for channels the viewer does not follow
 * and for signed-out viewers, and with nothing opted in it makes no request at all.
 *
 * The feed side ([followedLive]) is shared: a call within [shareWindowMs] of the last
 * successful fetch for the same account gets that answer instead of a new request.
 * It never announces anything. Both sides sit behind one lock, and the alert state is
 * saved before anything is announced, so the in-app timer and the background worker
 * can never announce the same stream twice.
 *
 * What [pollNow] sees go live is handed to [onGoLive] (a constructor callback, not a
 * flow, so an emission nobody was collecting cannot be lost while the state still
 * records the stream as seen).
 *
 * Failures never touch the state: a 401 or a network error is not "everyone went
 * offline".
 *
 * Cadence and stop conditions are the caller's: [pollEvery] loops until its coroutine
 * is cancelled, and each app cancels it when alerts are off, when nothing is opted
 * in, and (phone and TV) when the app leaves the screen.
 */
class LiveFollowsMonitor(
    private val fetchLive: suspend (userId: String) -> List<StreamInfo>,
    private val fetchWatched: suspend (logins: List<String>) -> List<StreamInfo>,
    private val store: LiveNotifyStateStore,
    private val settings: suspend () -> AppSettings,
    private val clock: () -> Long,
    private val onGoLive: suspend (List<StreamInfo>) -> Unit,
    private val shareWindowMs: Long = SHARE_WINDOW_MS,
) {
    private val mutex = Mutex()

    private val _liveNow = MutableStateFlow<List<StreamInfo>>(emptyList())

    /** The live follows from the latest successful feed fetch. */
    val liveNow: StateFlow<List<StreamInfo>> = _liveNow.asStateFlow()

    private class Answer(val userId: String, val streams: List<StreamInfo>, val atMs: Long)

    private var lastAnswer: Answer? = null
    private var state: LiveNotifyState? = null

    /** When [pollNow] last went to the network, successful or not; drives [pollEvery]. */
    @Volatile
    private var lastPollAtMs: Long = Long.MIN_VALUE / 2

    /** The opted-in logins at the last poll; a different list makes [pollEvery] poll at once. */
    @Volatile
    private var polledWatched: Set<String>? = null

    /**
     * Live follows for [userId], fetched now unless an answer younger than [maxAgeMs]
     * is at hand. Throws on failure, so callers keep their own fallbacks (the followed
     * rail falls back to per-channel checks). Never announces anything.
     */
    suspend fun followedLive(userId: String, maxAgeMs: Long = shareWindowMs): List<StreamInfo> =
        mutex.withLock {
            lastAnswer?.takeIf { it.userId == userId && clock() - it.atMs < maxAgeMs }?.let { return@withLock it.streams }
            val live = fetchLive(userId)
            lastAnswer = Answer(userId, live, clock())
            _liveNow.value = live
            live
        }

    /**
     * Alerts stopped: switched off, or no channel is opted in. Whatever goes live from
     * now until they resume is old news by then, so the first poll after that records
     * the live set without announcing it (see [GoLiveDetector], rule 5), and a channel
     * opted in again later is seeded afresh. Each app calls this when its "alerts
     * wanted" condition turns false, never when a poll loop merely restarts (the app
     * coming back on screen), which would swallow go-lives the background check had
     * not caught yet.
     */
    suspend fun alertsStopped() {
        mutex.withLock {
            val current = currentState()
            if (!current.seeded && current.watched.isEmpty()) return@withLock
            val next = current.copy(seeded = false, watched = emptySet())
            state = next
            withContext(NonCancellable) { saveState(next) }
        }
    }

    /**
     * The state in memory, or the saved one. An unreadable store reads as empty, which
     * seeds silently; a cancellation is passed on, because treating it as an empty
     * store would reseed and swallow the go-lives of the poll after it.
     */
    private suspend fun currentState(): LiveNotifyState {
        state?.let { return it }
        return try {
            store.load()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            LiveNotifyState()
        }
    }

    /**
     * Saves [next]; callers run it NonCancellable, because [state] already holds it. A
     * failed save only costs a possible repeat after a restart, never worth failing a poll.
     */
    private suspend fun saveState(next: LiveNotifyState) {
        runCatching { store.save(next) }
    }

    private fun watchedLogins(prefs: AppSettings): Set<String> =
        prefs.notifyChannels.mapNotNullTo(LinkedHashSet()) { it.trim().lowercase().takeIf(String::isNotEmpty) }

    /**
     * One fail-soft alert poll: a single batched request for the channels opted in to.
     * With alerts off or nothing opted in there is no request, and the state is reset
     * so the next opt-in starts from what is live then.
     */
    suspend fun pollNow() {
        val prefs = settings()
        val watched = watchedLogins(prefs)
        if (watched.isEmpty() || !prefs.liveNotifications) {
            polledWatched = watched
            alertsStopped()
            return
        }
        var announce = emptyList<StreamInfo>()
        try {
            mutex.withLock {
                // The worker and the in-app timer can both land here; one fetch serves both.
                if (watched == polledWatched && clock() - lastPollAtMs < shareWindowMs) return@withLock
                lastPollAtMs = clock()
                polledWatched = watched
                val live = fetchWatched(watched.toList())
                val decision = GoLiveDetector.evaluate(
                    prev = currentState(),
                    userId = "",
                    live = live,
                    nowMs = clock(),
                    enabled = true,
                    watched = watched,
                )
                state = decision.nextState
                // The state already counts these streams as announced, so a cancel arriving
                // now must neither skip the save nor the announcement below.
                withContext(NonCancellable) { saveState(decision.nextState) }
                announce = decision.toNotify
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // Kept as it was; the next tick tries again.
            return
        }
        if (announce.isNotEmpty()) {
            try {
                onGoLive(announce)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                // A notification that failed to show is not worth failing the poll.
            }
        }
    }

    /**
     * Polls every [intervalMs], counted from the last poll, until the calling coroutine
     * is cancelled; a changed opt-in list polls at once, so a channel added while
     * offline is already known when it goes live. A failed poll waits a full interval
     * too, so a dead network costs one request per interval, not a retry storm.
     */
    suspend fun pollEvery(intervalMs: Long): Nothing {
        while (true) {
            val changed = watchedLogins(settings()) != polledWatched
            val wait = if (changed) 0 else intervalMs - (clock() - lastPollAtMs)
            if (wait > 0) {
                delay(wait)
                continue
            }
            pollNow()
            delay(intervalMs)
        }
    }

    companion object {
        /** Live status this fresh is good enough for any screen; one request serves them all. */
        const val SHARE_WINDOW_MS = 30_000L
    }
}
