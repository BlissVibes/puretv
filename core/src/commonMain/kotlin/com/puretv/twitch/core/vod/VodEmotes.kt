package com.puretv.twitch.core.vod

import com.puretv.twitch.core.emotes.EmoteRepository
import com.puretv.twitch.core.emotes.ResolvedEmote
import com.puretv.twitch.core.emotes.buildEmoteIndex
import com.puretv.twitch.core.repository.ChannelRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The broadcaster's channel and the global third-party (7TV/BTTV/FFZ) emote sets
 * as a code to emote index, for [VodChatReplayController.loadEmoteIndex]. Each
 * source is best-effort: one that fails just leaves its emotes out.
 */
suspend fun loadVodEmoteIndex(
    channelLogin: String,
    channelRepository: ChannelRepository,
    emoteRepository: EmoteRepository,
): Map<String, ResolvedEmote> = coroutineScope {
    val channel = runCatching { channelRepository.getChannel(channelLogin) }.getOrNull()
    val global = async { runCatching { emoteRepository.loadGlobalEmotes() }.getOrDefault(emptyList()) }
    val channelEmotes = async {
        if (channel != null) runCatching { emoteRepository.loadChannelEmotes(channel.id, channel.login) }.getOrDefault(emptyList())
        else emptyList()
    }
    buildEmoteIndex(thirdPartyChannel = channelEmotes.await(), thirdPartyGlobal = global.await())
}
