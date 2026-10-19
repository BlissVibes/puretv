package com.puretv.twitch.core.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlin.jvm.Synchronized
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Resolved playable streams (playback token + master playlist), shared app-wide
 * and kept for [ttl].
 *
 * Opening a stream costs a GQL token mint plus a usher fetch before the player
 * can even start. This cache takes that off the critical path:
 *   - [prewarm] starts resolving a channel the viewer is about to open (a hovered
 *     card on Windows, a focused card on TV), so the tap finds it ready;
 *   - [get] joins a resolution already in flight instead of starting a second
 *     one, and reuses a recent result on a quick re-open or a quality change;
 *   - [masterBody] hands the player's network layer the master it already
 *     fetched, saving one more usher round trip.
 *
 * [ttl] stays well inside the playback token's lifetime. A failed resolution is
 * never cached: the next [get] tries again.
 */
class PlayableStreamCache(
    private val streamResolver: StreamResolver,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ttl: Duration = 60.seconds,
    private val maxEntries: Int = 8,
) {
    private class Entry(val result: Deferred<MasterPlaylistResult>, val createdAt: TimeMark)

    private class Body(val content: String, val fetchedAt: TimeMark)

    // Insertion order doubles as age order, so the first entry is the oldest.
    private val entries = LinkedHashMap<String, Entry>()
    // Master bodies by master URL, recorded as each resolution completes.
    private val bodies = LinkedHashMap<String, Body>()

    /** Resolves [channelLogin], reusing a fresh or in-flight resolution when there is one. */
    suspend fun get(channelLogin: String): MasterPlaylistResult {
        val entry = entryFor(channelLogin.lowercase())
        return try {
            entry.result.await()
        } catch (e: Throwable) {
            forget(channelLogin.lowercase(), entry)
            throw e
        }
    }

    /** Starts resolving [channelLogin] in the background. Errors are dropped; [get] retries. */
    fun prewarm(channelLogin: String) {
        if (channelLogin.isBlank()) return
        entryFor(channelLogin.lowercase())
    }

    /** Drops [channelLogin] so the next [get] mints a fresh session (retry after a playback failure). */
    @Synchronized
    fun invalidate(channelLogin: String) {
        entries.remove(channelLogin.lowercase())
    }

    /**
     * The master body for exactly [masterUrl] while it is fresh, else null.
     * Non-suspending so an OkHttp interceptor thread can call it directly.
     */
    @Synchronized
    fun masterBody(masterUrl: String): String? {
        val body = bodies[masterUrl] ?: return null
        if (body.fetchedAt.elapsedNow() < ttl) return body.content
        bodies.remove(masterUrl)
        return null
    }

    @Synchronized
    private fun entryFor(key: String): Entry {
        entries[key]?.let { existing ->
            // A failed Deferred reports isCancelled; never hand it out again.
            if (!existing.result.isCancelled && existing.createdAt.elapsedNow() < ttl) return existing
            entries.remove(key)
        }
        val entry = Entry(
            result = scope.async {
                streamResolver.resolveMasterPlaylist(key, playerType = StreamResolver.PRIMARY_PLAYER_TYPE)
                    .also { recordBody(it) }
            },
            createdAt = timeSource.markNow(),
        )
        entries[key] = entry
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
        return entry
    }

    @Synchronized
    private fun forget(key: String, entry: Entry) {
        if (entries[key] === entry) entries.remove(key)
    }

    @Synchronized
    private fun recordBody(result: MasterPlaylistResult) {
        bodies[result.masterUrl] = Body(result.rawContent, timeSource.markNow())
        while (bodies.size > maxEntries) bodies.remove(bodies.keys.first())
    }
}
