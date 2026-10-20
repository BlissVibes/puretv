package com.puretv.twitch.core.notify

import com.puretv.twitch.core.model.StreamInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One go-live banner. [key] is the lowercased login, or a batch key for a summary. */
data class GoLiveBanner(val key: String, val title: String, val body: String)

/**
 * The TV's go-live banners waiting to be shown, oldest first; the head is the one on
 * screen. The banner itself owns the dwell time and calls [dismiss] when it is done,
 * so this stays a plain list with the rules that matter:
 *
 *  - the channel being watched is never announced;
 *  - a channel already waiting is not queued twice;
 *  - more than [GoLiveAlerts.MAX_SEPARATE] at once become one summary banner;
 *  - at most [maxPending] wait, dropping the oldest WAITING ones (never the one on
 *    screen), so a long stretch of alerts cannot build an evening-long queue.
 *
 * Writers (the poll, on IO) and the banner (main thread) race, so every change is a
 * single atomic [MutableStateFlow.update].
 */
class GoLiveBannerQueue(private val maxPending: Int = MAX_PENDING) {
    private val _pending = MutableStateFlow<List<GoLiveBanner>>(emptyList())
    val pending: StateFlow<List<GoLiveBanner>> = _pending.asStateFlow()

    fun offer(streams: List<StreamInfo>, watchingLogin: String?) {
        val fresh = GoLiveAlerts.exceptWatching(streams, watchingLogin)
        if (fresh.isEmpty()) return
        val banners = if (fresh.size > GoLiveAlerts.MAX_SEPARATE) {
            val message = GoLiveText.forBatch(fresh)
            listOf(GoLiveBanner("batch:" + fresh.joinToString(",") { it.userLogin.lowercase() }, message.title, message.body))
        } else {
            fresh.map { stream ->
                val message = GoLiveText.forStream(stream)
                GoLiveBanner(stream.userLogin.lowercase(), message.title, message.body)
            }
        }
        _pending.update { queue ->
            val waiting = queue.mapTo(HashSet()) { it.key }
            val merged = queue + banners.filter { it.key !in waiting }
            if (merged.size <= maxPending) merged else listOf(merged.first()) + merged.takeLast(maxPending - 1)
        }
    }

    /** Takes [banner] off the queue once it has been shown; the next one follows. */
    fun dismiss(banner: GoLiveBanner) {
        _pending.update { queue -> queue.filterNot { it.key == banner.key } }
    }

    fun clear() {
        _pending.value = emptyList()
    }

    companion object {
        const val MAX_PENDING = 4
    }
}
