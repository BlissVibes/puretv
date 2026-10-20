package com.puretv.twitch.core.vod

import com.puretv.twitch.core.emotes.ResolvedEmote
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.stream.CommentBatch
import com.puretv.twitch.core.stream.ReplayBuffer
import com.puretv.twitch.core.stream.withThirdPartyEmotes
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives VOD chat replay for every app: buffers comments (deduped by id) and
 * exposes those due at the current playback position, prefetching forward and
 * resetting on a backward seek.
 *
 * The caller feeds the playback position into [onPosition] (about once a second
 * is plenty) and shows [messages]. It never touches the player, so it is the
 * same code on Windows, the phone and the TV.
 *
 * Third-party (7TV/BTTV/FFZ) emotes are tokenized against the broadcaster's emote
 * sets when the due window is published (only the visible comments, so it stays
 * cheap). The emote index loads in PARALLEL, so chat and position tracking never
 * block on it; until it lands, comments render with Twitch-native emotes only,
 * then re-emit tokenized once it is ready (mirrors live chat).
 *
 * Positions are handled one at a time, in order, by a single collector under
 * [lock]: the callers' scopes can be multi-threaded, and a coroutine per tick
 * could reorder them.
 */
class VodChatReplayController(
    private val scope: CoroutineScope,
    /** Comments from [offsetSeconds] on (the VOD comments API pages by offset). */
    private val fetch: suspend (offsetSeconds: Int) -> CommentBatch,
    /** The broadcaster's channel and global third-party emotes, by code. */
    private val loadEmoteIndex: suspend () -> Map<String, ResolvedEmote>,
) {
    private val buffer = ReplayBuffer()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // Surfaced when a comment fetch fails so the screen can show an error instead
    // of a permanently-blank replay panel. Cleared by the next good load.
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val position = MutableStateFlow(NO_POSITION)

    // Load lifecycle + buffer mutation state, all under [lock] so the position
    // collector and the load coroutine never race on it.
    private val lock = Mutex()
    private var loading = false
    private var loadJob: Job? = null
    // Bumped on a backward-seek reset; a load captures it at launch and discards
    // its result if it has since moved on, so an in-flight forward prefetch can't
    // dump pre-seek comments into the freshly reset buffer.
    private var generation = 0
    private var exhausted = false   // no more pages after the loaded window

    @Volatile private var lastSec = NO_POSITION
    // Published once when the parallel load finishes; immutable map after publish.
    @Volatile private var emoteIndex: Map<String, ResolvedEmote> = emptyMap()

    init {
        scope.launch {
            emoteIndex = runCatching { loadEmoteIndex() }.getOrDefault(emptyMap())
            emitDue(lastSec.coerceAtLeast(0))
        }
        scope.launch { lock.withLock { requestLoad(0) } }
        scope.launch {
            position.filter { it >= 0 }.collect { sec -> lock.withLock { onSecond(sec) } }
        }
    }

    /** The player is at [seconds]. Safe to call as often as you like; equal values are ignored. */
    fun onPosition(seconds: Long) {
        position.value = seconds.coerceAtLeast(0)
    }

    // Must hold [lock].
    private fun onSecond(sec: Long) {
        val backward = lastSec != NO_POSITION && sec < lastSec - BACKWARD_SEEK_SECONDS
        lastSec = sec
        when {
            // Backward seek: chat for that point may differ; reload from scratch.
            // Void any in-flight load (its window is now stale) AND clear the loading
            // guard so the reload below isn't blocked by it.
            backward -> {
                generation++
                loadJob?.cancel()
                loadJob = null
                loading = false
                buffer.reset()
                exhausted = false
                requestLoad(sec.toInt())
            }
            buffer.isEmpty() -> requestLoad(sec.toInt())
            !exhausted && sec.toInt() >= buffer.maxOffsetSeconds - PREFETCH_LEAD_SECONDS ->
                requestLoad(buffer.maxOffsetSeconds)
        }
        emitDue(sec)
    }

    /** Publishes the due window, tokenizing only the visible comments against the current index. */
    private fun emitDue(sec: Long) {
        _messages.value = buffer.due(sec).takeLast(MAX_VISIBLE).withThirdPartyEmotes(emoteIndex).map { it.message }
    }

    /** Fetch a window; guarded so overlapping ticks don't stack requests. Must hold [lock]. */
    private fun requestLoad(offsetSeconds: Int) {
        if (loading) return
        loading = true
        val gen = generation
        loadJob = scope.launch {
            val result = runCatching { fetch(offsetSeconds) }
            val applied = lock.withLock {
                // Drop a load superseded by a backward-seek reset: its comments belong
                // to a window we've already thrown away, and the current generation now
                // owns the loading flag + buffer/error state.
                if (gen != generation) return@withLock false
                result
                    .onSuccess { batch ->
                        buffer.add(batch.comments)
                        if (!batch.hasNextPage) exhausted = true
                        _error.value = null
                    }
                    .onFailure {
                        // Surface the failure instead of leaving the panel silently blank.
                        _error.value = ERROR_TEXT
                    }
                loading = false
                true
            }
            if (applied) emitDue(lastSec.coerceAtLeast(0))
        }
    }

    companion object {
        const val ERROR_TEXT = "Couldn't load chat replay. It may catch up shortly."
        private const val NO_POSITION = -1L
        private const val BACKWARD_SEEK_SECONDS = 3
        private const val PREFETCH_LEAD_SECONDS = 20
        private const val MAX_VISIBLE = 200
    }
}
