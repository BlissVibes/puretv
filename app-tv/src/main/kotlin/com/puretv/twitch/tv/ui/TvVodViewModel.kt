package com.puretv.twitch.tv.ui

import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import com.puretv.twitch.core.model.StreamQuality
import com.puretv.twitch.core.repository.VodRepository
import com.puretv.twitch.core.vod.VodFailures
import com.puretv.twitch.tv.data.AppSettingsStore
import com.puretv.twitch.tv.player.TvPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the VOD player needs at launch: the id to play, plus display
 * metadata so a resume entry needs no extra API call. Same fields as the phone
 * and Windows `VodLaunch`.
 */
data class VodLaunch(
    val vodId: String,
    val channelLogin: String,
    val title: String,
    val thumbnailUrl: String,
)

data class TvVodUiState(
    /** The usher master being played; null until it resolves. */
    val playableUrl: String? = null,
    /** Why the video will not play, or null while it is fine. */
    val error: String? = null,
    /** True when trying again cannot help (deleted, subscribers only). */
    val errorIsPermanent: Boolean = false,
)

/**
 * Plays one past broadcast on the shared [TvPlayer]. The usher master goes
 * straight to [TvPlayer.playVod]: VOD manifests carry no ads, so nothing here
 * touches the live ad pipeline, and every seek is a plain seekTo.
 *
 * Unlike the live screen, this view model owns the player's teardown, so the
 * position can still be read when the viewer leaves.
 */
@OptIn(UnstableApi::class)
class TvVodViewModel(
    private val launch: VodLaunch,
    private val vodRepository: VodRepository,
    private val tvPlayer: TvPlayer,
    private val settingsStore: AppSettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(TvVodUiState())
    val state: StateFlow<TvVodUiState> = _state.asStateFlow()

    private val gaveUpListener: (PlaybackException) -> Unit = { onPlaybackGaveUp() }
    private var freshSessionRetries = 0

    init {
        tvPlayer.onGaveUp = gaveUpListener
        viewModelScope.launch {
            // Audio only is a live-stream choice: a VOD always opens with its picture.
            val preferred = StreamQuality.fromSetting(settingsStore.flow.first().preferredQuality)
                .takeIf { it != StreamQuality.AUDIO_ONLY } ?: StreamQuality.AUTO
            runCatching { tvPlayer.setQuality(preferred) }
            play(startPositionMs = 0L)
        }
    }

    /** Resolves the video (anonymous token, see [VodFailures]) and starts it at [startPositionMs]. */
    private suspend fun play(startPositionMs: Long) {
        _state.update { it.copy(error = null, errorIsPermanent = false) }
        runCatching { vodRepository.resolvePlayableVod(launch.vodId) }
            .onSuccess { master ->
                tvPlayer.playVod(master.masterUrl, startPositionMs)
                _state.update { it.copy(playableUrl = master.masterUrl) }
            }
            .onFailure { e ->
                _state.update { it.copy(error = VodFailures.message(e), errorIsPermanent = VodFailures.isPermanent(e)) }
            }
    }

    /** After a failure (OK on the remote): a fresh session, picking up where playback stopped. */
    fun retry() {
        freshSessionRetries = 0
        val at = currentPositionMs()
        viewModelScope.launch { play(at) }
    }

    /**
     * The player spent its backed-off retries. The signed URL may have expired
     * during a long pause, so resolve a fresh one once and carry on from the
     * same spot; if that fails too, say so instead of staying black.
     */
    private fun onPlaybackGaveUp() {
        val at = currentPositionMs()
        viewModelScope.launch {
            if (freshSessionRetries < MAX_FRESH_SESSION_RETRIES) {
                freshSessionRetries++
                play(at)
            } else {
                _state.update {
                    it.copy(
                        error = "This video stopped playing. PureTV tried to reconnect but couldn't. " +
                            "Check your connection, then press OK to try again.",
                        errorIsPermanent = false,
                    )
                }
            }
        }
    }

    private fun currentPositionMs(): Long =
        runCatching { tvPlayer.exoPlayer.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)

    /** True while the shared player still holds this video (a new screen may already own it). */
    private fun playerIsOurs(): Boolean {
        val url = _state.value.playableUrl ?: return false
        return tvPlayer.isVod && tvPlayer.exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString() == url
    }

    override fun onCleared() {
        super.onCleared()
        if (tvPlayer.onGaveUp === gaveUpListener) tvPlayer.onGaveUp = null
        // Leaving must stop the singleton (it plays with playWhenReady=true), but
        // only if it is still ours: a screen opened right after may own it now.
        if (playerIsOurs()) tvPlayer.stop()
    }

    private companion object {
        const val MAX_FRESH_SESSION_RETRIES = 1
    }
}
