package com.puretv.twitch.core.notify

import com.puretv.twitch.core.model.AppSettings
import com.puretv.twitch.core.model.StreamInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one place each app asks Twitch which followed channels are live
 * (`GET /streams/followed`), and the one place go-live alerts come from.
 *
 * Every screen that shows live follows (the Windows followed rail, Home "Live now" on
 * phone and TV, the Following screens) reads through [followedLive], and the alert
 * timer runs [pollEvery]. They share one answer: a call within [shareWindowMs] of the
 * last successful fetch for the same account gets that answer instead of a new
 * request, and the timer counts its interval from the last fetch made by anyone, so a
 * screen that is already refreshing never doubles the Helix calls.
 *
 * Every successful fetch, whoever asked for it, runs [GoLiveDetector] and hands what
 * just went live to [onGoLive]. That is why the notifier is a constructor callback
 * and not a flow: a go-live first seen by Home must still be announced, and an
 * emission nobody was collecting would be lost while the state still recorded the
 * stream as seen.
 *
 * Failures never touch the state: a 401 or a network error is not "everyone went
 * offline". Signed out ([userId] null or blank), nothing is fetched.
 *
 * Cadence and stop conditions are the caller's: [pollEvery] loops until its coroutine
 * is cancelled, and each app cancels it when alerts are off, when signed out, and
 * (phone and TV) when the app leaves the screen.
 */
class LiveFollowsMonitor(
    private val fetchLive: suspend (userId: String) -> List<StreamInfo>,
    private val store: LiveNotifyStateStore,
    private val settings: suspend () -> AppSettings,
    private val userId: suspend () -> String?,
    private val clock: () -> Long,
    private val onGoLive: suspend (List<StreamInfo>) -> Unit,
    private val shareWindowMs: Long = SHARE_WINDOW_MS,
) {
    private val mutex = Mutex()

    private val _liveNow = MutableStateFlow<List<StreamInfo>>(emptyList())

    /** The live follows from the latest successful fetch; empty when signed out. */
    val liveNow: StateFlow<List<StreamInfo>> = _liveNow.asStateFlow()

    private class Answer(val userId: String, val streams: List<StreamInfo>, val atMs: Long)

    private var lastAnswer: Answer? = null
    private var state: LiveNotifyState? = null

    /** When anyone last went to the network, successful or not; drives [pollEvery]. */
    @Volatile
    private var lastFetchAttemptAtMs: Long = Long.MIN_VALUE / 2

    /**
     * Live follows for [userId], fetched now unless an answer younger than [maxAgeMs]
     * is at hand. Throws on failure, so callers keep their own fallbacks (the followed
     * rail falls back to per-channel checks) and state is never written.
     */
    suspend fun followedLive(userId: String, maxAgeMs: Long = shareWindowMs): List<StreamInfo> {
        var announce = emptyList<StreamInfo>()
        val streams = mutex.withLock {
            lastAnswer?.takeIf { it.userId == userId && clock() - it.atMs < maxAgeMs }?.let { return@withLock it.streams }
            lastFetchAttemptAtMs = clock()
            val live = fetchLive(userId)
            val now = clock()
            lastAnswer = Answer(userId, live, now)
            _liveNow.value = live
            val prefs = settings()
            val decision = GoLiveDetector.evaluate(
                prev = state ?: runCatching { store.load() }.getOrDefault(LiveNotifyState()),
                userId = userId,
                live = live,
                nowMs = now,
                muted = prefs.notifyMutedLogins,
                enabled = prefs.liveNotifications,
            )
            state = decision.nextState
            runCatching { store.save(decision.nextState) }
            announce = decision.toNotify
            live
        }
        if (announce.isNotEmpty()) {
            try {
                onGoLive(announce)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                // A notification that failed to show is not worth failing the caller's load.
            }
        }
        return streams
    }

    /** One fail-soft poll for the signed-in account. Signed out, clears [liveNow] without a request. */
    suspend fun pollNow() {
        val uid = userId()?.takeIf { it.isNotBlank() }
        if (uid == null) {
            mutex.withLock {
                lastAnswer = null
                _liveNow.value = emptyList()
            }
            return
        }
        try {
            followedLive(uid)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // Kept as it was; the next tick tries again.
        }
    }

    /**
     * Polls every [intervalMs], counted from the last fetch by anyone, until the calling
     * coroutine is cancelled. A failed poll waits a full interval too, so a dead network
     * costs one request per interval, not a retry storm.
     */
    suspend fun pollEvery(intervalMs: Long): Nothing {
        while (true) {
            val wait = intervalMs - (clock() - lastFetchAttemptAtMs)
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
