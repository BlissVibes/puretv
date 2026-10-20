package com.puretv.twitch.desktop.ui

import com.puretv.twitch.core.emotes.EmoteRepository
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.repository.ChannelRepository
import com.puretv.twitch.core.repository.VodRepository
import com.puretv.twitch.core.vod.VodChatReplayController
import com.puretv.twitch.core.vod.loadVodEmoteIndex
import com.puretv.twitch.desktop.player.DesktopPlayer
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Windows side of VOD chat replay. The replay rules (buffering, prefetching,
 * backward seeks) live in core's [VodChatReplayController], shared with the
 * phone and TV; this feeds it the player's position and the Windows data
 * sources. Isolated from [VodPlayerViewModel] so chat is independently cuttable.
 */
class VodChatViewModel(
    private val vodId: String,
    private val channelLogin: String,
    private val player: DesktopPlayer,
    private val vodRepository: VodRepository,
    private val channelRepository: ChannelRepository,
    private val emoteRepository: EmoteRepository,
) : DesktopViewModel() {

    private val controller = VodChatReplayController(
        scope = scope,
        fetch = { offsetSeconds -> vodRepository.videoComments(vodId, offsetSeconds) },
        loadEmoteIndex = { loadVodEmoteIndex(channelLogin, channelRepository, emoteRepository) },
    )

    val messages: StateFlow<List<ChatMessage>> = controller.messages

    /** Set when a comment fetch fails, so the screen can say so; cleared by the next good load. */
    val error: StateFlow<String?> = controller.error

    init {
        scope.launch {
            player.status.collect { st -> controller.onPosition(st.positionMs / 1000) }
        }
    }
}
