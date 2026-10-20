package com.puretv.twitch.tv.ui

import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import com.puretv.twitch.core.model.StreamQuality
import com.puretv.twitch.core.emotes.EmoteRepository
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.repository.ChannelRepository
import com.puretv.twitch.core.repository.VodRepository
import com.puretv.twitch.core.stream.Storyboard
import com.puretv.twitch.core.vod.ResumePolicy
import com.puretv.twitch.core.vod.VodChatReplayController
import com.puretv.twitch.core.vod.VodFailures
import com.puretv.twitch.core.vod.loadVodEmoteIndex
import com.puretv.twitch.core.vod.WatchProgress
import com.puretv.twitch.tv.data.db.VodProgressDao
import com.puretv.twitch.tv.data.db.toCore
import com.puretv.twitch.tv.data.db.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    /** Where a saved watch left off, offered as "Resume". Null when there is none worth resuming. */
    val resumeOfferMs: Long? = null,
    /** Seek preview frames, loaded once per video; null when it has none. */
    val storyboard: Storyboard? = null,
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
    private val progressDao: VodProgressDao,
    channelRepository: ChannelRepository,
    emoteRepository: EmoteRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TvVodUiState())
    val state: StateFlow<TvVodUiState> = _state.asStateFlow()

    /** Chat as it was said, following the playback position (fed by [onChatPosition]). */
    private val chatReplay = VodChatReplayController(
        scope = viewModelScope,
        fetch = { offsetSeconds -> vodRepository.videoComments(launch.vodId, offsetSeconds) },
        loadEmoteIndex = { loadVodEmoteIndex(launch.channelLogin, channelRepository, emoteRepository) },
    )
    val chatMessages: StateFlow<List<ChatMessage>> = chatReplay.messages
    val chatError: StateFlow<String?> = chatReplay.error

    /** The screen ticks this about once a second, and only while the chat pane is showing. */
    fun onChatPosition(seconds: Long) = chatReplay.onPosition(seconds)

    private val gaveUpListener: (PlaybackException) -> Unit = { onPlaybackGaveUp() }
    private var freshSessionRetries = 0

    // Where to start once the video resolves, when Resume was tapped before it had.
    private var startAtMs: Long? = null

    // Where the viewer was when something else (a go-live stream) took the player.
    private var lastPositionMs = 0L
    private var quality = StreamQuality.AUTO

    init {
        tvPlayer.onGaveUp = gaveUpListener
        viewModelScope.launch {
            val saved = runCatching { progressDao.get(launch.vodId)?.toCore() }.getOrNull()
            _state.update { it.copy(resumeOfferMs = saved?.let(ResumePolicy::resumePositionMs)) }
        }
        viewModelScope.launch {
            runCatching { vodRepository.loadStoryboard(launch.vodId) }
                .onSuccess { sb -> _state.update { it.copy(storyboard = sb) } }
        }
        // Save every 10 s while playing. Each save is guarded, so one failed write
        // does not end the loop for the rest of the video.
        viewModelScope.launch {
            while (true) {
                delay(SAVE_EVERY_MS)
                // A video that played to here has recovered: give the next hiccup its retry again.
                if (playerIsOurs() && tvPlayer.exoPlayer.isPlaying) freshSessionRetries = 0
                val progress = snapshot() ?: continue
                runCatching { progressDao.upsert(progress.toEntity()) }
            }
        }
        viewModelScope.launch {
            // Audio only is a live-stream choice: a VOD always opens with its picture.
            val preferred = StreamQuality.fromSetting(settingsStore.flow.first().preferredQuality)
                .takeIf { it != StreamQuality.AUDIO_ONLY } ?: StreamQuality.AUTO
            quality = preferred
            runCatching { tvPlayer.setQuality(preferred) }
            play(startPositionMs = 0L)
        }
    }

    /** Resolves the video (anonymous token, see [VodFailures]) and starts it at [startPositionMs]. */
    private suspend fun play(startPositionMs: Long) {
        _state.update { it.copy(error = null, errorIsPermanent = false) }
        runCatching { vodRepository.resolvePlayableVod(launch.vodId) }
            .onSuccess { master ->
                // A Resume tapped before the video loaded takes over the start.
                val start = startAtMs ?: startPositionMs
                startAtMs = null
                tvPlayer.playVod(master.masterUrl, start)
                tvPlayer.onGaveUp = gaveUpListener
                _state.update { it.copy(playableUrl = master.masterUrl) }
            }
            .onFailure { e ->
                var message = VodFailures.message(e)
                // A deleted video can never be resumed, so its Continue watching card goes too.
                if (VodFailures.isConfirmedGone(e)) {
                    val removed = runCatching {
                        val had = progressDao.get(launch.vodId) != null
                        if (had) progressDao.delete(launch.vodId)
                        had
                    }.getOrDefault(false)
                    if (removed) message += " It has been removed from Continue watching."
                }
                _state.update { it.copy(error = message, errorIsPermanent = VodFailures.isPermanent(e)) }
            }
    }

    /** Jumps to the saved position. A plain seek: the video is already playing. */
    fun resume() {
        val at = _state.value.resumeOfferMs ?: return
        _state.update { it.copy(resumeOfferMs = null) }
        if (_state.value.playableUrl == null) {
            startAtMs = at
        } else {
            runCatching { tvPlayer.exoPlayer.seekTo(at) }
        }
    }

    /** The screen is leaving the foreground: remember where we were in case a stream takes the player. */
    fun onScreenPaused() {
        if (playerIsOurs()) lastPositionMs = currentPositionMs()
    }

    /**
     * The screen is back in front. If a stream stacked on top (a go-live alert) has
     * taken the player and emptied it on its way out, start this video again where it
     * was. A plain seek is not enough: the media is gone, so it is a fresh playVod.
     */
    fun onScreenResumed() {
        val url = _state.value.playableUrl ?: return
        if (_state.value.error != null || playerIsOurs()) return
        tvPlayer.playVod(url, lastPositionMs)
        runCatching { tvPlayer.setQuality(quality) }
        tvPlayer.onGaveUp = gaveUpListener
    }

    fun startOver() = _state.update { it.copy(resumeOfferMs = null) }

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

    /**
     * Where the viewer is now, or null when there is nothing worth saving yet
     * (still loading, or this video no longer owns the player). Main thread only.
     */
    private fun snapshot(): WatchProgress? {
        if (!playerIsOurs()) return null
        val player = tvPlayer.exoPlayer
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return null
        val position = player.currentPosition
        if (position <= 0) return null
        // An unanswered "Resume from ..." still points at the saved spot: playing the
        // first seconds must not overwrite it before the viewer has chosen.
        val offer = _state.value.resumeOfferMs
        if (offer != null && position <= offer) return null
        return WatchProgress(
            vodId = launch.vodId,
            positionMs = position,
            durationMs = duration,
            updatedAt = System.currentTimeMillis(),
            title = launch.title,
            channelLogin = launch.channelLogin,
            thumbnailUrl = launch.thumbnailUrl,
        )
    }

    override fun onCleared() {
        super.onCleared()
        // Read the position BEFORE stopping: stop() clears the media items and with
        // them the position. viewModelScope is already cancelled here, so the write
        // goes through a scope that outlives this view model.
        val last = snapshot()
        if (last != null) {
            val dao = progressDao
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching {
                    dao.upsert(last.toEntity())
                    dao.prune()
                }
            }
        }
        // Leaving must stop the singleton (it plays with playWhenReady=true), but
        // only if it is still ours: a screen opened right after may own it now.
        if (playerIsOurs()) tvPlayer.stop()
        if (tvPlayer.onGaveUp === gaveUpListener) tvPlayer.onGaveUp = null
    }

    private companion object {
        const val MAX_FRESH_SESSION_RETRIES = 1
        const val SAVE_EVERY_MS = 10_000L
    }
}
