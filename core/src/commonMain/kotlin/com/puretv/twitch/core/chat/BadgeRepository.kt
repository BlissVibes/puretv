package com.puretv.twitch.core.chat

import com.puretv.twitch.core.api.ChatBadgeSetDto
import com.puretv.twitch.core.api.TwitchApiClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A resolved chat badge image: a CDN URL plus its human title (tooltip/alt text). */
data class ChatBadgeImage(val url: String, val title: String)

/**
 * Immutable lookup from (badge set id, version) -> [ChatBadgeImage], built by
 * merging a channel's badge sets over the global sets. Used to render real badge
 * icons (Twitch/sub/mod/bits) in chat instead of text chips.
 */
class BadgeIndex(private val bySet: Map<String, Map<String, ChatBadgeImage>>) {
    /** Resolve a badge to its image, or null if the set/version is unknown. */
    fun resolve(setId: String, version: String): ChatBadgeImage? = bySet[setId]?.get(version)

    companion object {
        val EMPTY = BadgeIndex(emptyMap())

        /**
         * Build an index from global + channel badge sets. Channel sets win on a
         * set-id collision (a channel's "subscriber" badge overrides the global one),
         * so they are applied last.
         */
        fun from(global: List<ChatBadgeSetDto>, channel: List<ChatBadgeSetDto>): BadgeIndex {
            val out = LinkedHashMap<String, Map<String, ChatBadgeImage>>()
            for (set in global + channel) {
                if (set.setId.isBlank()) continue
                val versions = LinkedHashMap<String, ChatBadgeImage>()
                for (v in set.versions) {
                    if (v.id.isBlank()) continue
                    // Prefer the 2x art for chat (crisp at ~18px on HiDPI); fall back down the ladder.
                    val url = v.imageUrl2x.ifBlank { v.imageUrl4x.ifBlank { v.imageUrl1x } }
                    if (url.isNotBlank()) versions[v.id] = ChatBadgeImage(url, v.title)
                }
                if (versions.isNotEmpty()) out[set.setId] = versions
            }
            return BadgeIndex(out)
        }
    }
}

/**
 * Loads Twitch chat badge sets for a channel and assembles a [BadgeIndex].
 * Best-effort: a failed fetch yields an empty set so chat still renders (just
 * without that tier of badge art). The global sets rarely change, so they are
 * fetched once per session and fetched alongside the channel's, not before it.
 */
class BadgeRepository(private val api: TwitchApiClient) {
    private val globalLock = Mutex()
    private var globalSets: List<ChatBadgeSetDto>? = null

    suspend fun loadForChannel(broadcasterId: String): BadgeIndex = coroutineScope {
        val global = async { globalBadgeSets() }
        val channel = async { api.getChannelChatBadges(broadcasterId) }
        BadgeIndex.from(global.await(), channel.await())
    }

    private suspend fun globalBadgeSets(): List<ChatBadgeSetDto> {
        globalLock.withLock { globalSets }?.let { return it }
        val fresh = api.getGlobalChatBadges()
        // The API call is best-effort and answers a failure with an empty list;
        // only a real answer is kept, so a failure is retried on the next open.
        if (fresh.isNotEmpty()) globalLock.withLock { globalSets = fresh }
        return fresh
    }
}
