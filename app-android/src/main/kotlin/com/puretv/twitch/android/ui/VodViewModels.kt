package com.puretv.twitch.android.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import com.puretv.twitch.android.data.AppSettingsStore
import com.puretv.twitch.android.data.db.VodProgressDao
import com.puretv.twitch.android.data.db.toCore
import com.puretv.twitch.android.data.db.toEntity
import com.puretv.twitch.android.player.BackgroundPlayback
import com.puretv.twitch.android.player.TwitchPlayer
import com.puretv.twitch.core.model.StreamQuality
import com.puretv.twitch.core.repository.VodRepository
import com.puretv.twitch.core.stream.Storyboard
import com.puretv.twitch.core.vod.ResumePolicy
import com.puretv.twitch.core.vod.VodFailures
import com.puretv.twitch.core.vod.WatchProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the VOD player needs at launch: the id to play, plus display
 * metadata so the notification and a resume entry need no extra API call.
 * Same fields as the Windows `VodLaunch`.
 */
data class VodLaunch(
    val vodId: String,
    val channelLogin: String,
    val title: String,
    val thumbnailUrl: String,
)

data class VodPlayerUiState(
    /** The usher master being played; null until it resolves. */
    val playableUrl: String? = null,
    /** Why the video will not play, or null while it is fine. */
    val error: String? = null,
    /** True when trying again cannot help (deleted, subscribers only). */
    val errorIsPermanent: Boolean = false,
    /** What the player plays at: the saved preference on open, then the viewer's pick. */
    val quality: StreamQuality = StreamQuality.AUTO,
    /** Where a saved watch left off, offered as "Resume from ...". Null when there is none worth resuming. */
    val resumeOfferMs: Long? = null,
    /** Seek preview frames, loaded once per video; null when it has none. */
    val storyboard: Storyboard? = null,
)

/**
 * Plays one past broadcast on the shared [TwitchPlayer]. The usher master is
 * played directly through [TwitchPlayer.playVod]: VOD manifests carry no ads,
 * so nothing here touches the live ad pipeline, and every seek is a plain
 * seekTo on the player.
 */
@OptIn(UnstableApi::class)
class VodPlayerViewModel(
    private val launch: VodLaunch,
    private val vodRepository: VodRepository,
    private val twitchPlayer: TwitchPlayer,
    private val backgroundPlayback: BackgroundPlayback,
    private val settingsStore: AppSettingsStore,
    private val progressDao: VodProgressDao,
) : ViewModel() {
    private val _state = MutableStateFlow(VodPlayerUiState())
    val state: StateFlow<VodPlayerUiState> = _state.asStateFlow()

    private val gaveUpListener: (PlaybackException) -> Unit = { onPlaybackGaveUp() }
    private var freshSessionRetries = 0

    /** What the media notification and lock screen show. */
    private val metadata: MediaMetadata = MediaMetadata.Builder()
        .setTitle(launch.title.ifBlank { "Past broadcast" })
        .setArtist(launch.channelLogin)
        .setArtworkUri(launch.thumbnailUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
        .build()

    init {
        twitchPlayer.onGaveUp = gaveUpListener
        viewModelScope.launch {
            val saved = runCatching { progressDao.get(launch.vodId)?.toCore() }.getOrNull()
            _state.update { it.copy(resumeOfferMs = saved?.let(ResumePolicy::resumePositionMs)) }
        }
        viewModelScope.launch {
            runCatching { vodRepository.loadStoryboard(launch.vodId) }
                .onSuccess { sb -> _state.update { it.copy(storyboard = sb) } }
        }
        // Save every 10 s while playing. Each save is guarded, so one failed write
        // (full disk) does not end the loop for the rest of the video.
        viewModelScope.launch {
            while (true) {
                delay(SAVE_EVERY_MS)
                val progress = snapshot() ?: continue
                runCatching { progressDao.upsert(progress.toEntity()) }
            }
        }
        viewModelScope.launch {
            // Audio only is a live-stream choice: a VOD always opens with its picture.
            val preferred = StreamQuality.fromSetting(settingsStore.flow.first().preferredQuality)
                .takeIf { it != StreamQuality.AUDIO_ONLY } ?: StreamQuality.AUTO
            twitchPlayer.setQuality(preferred)
            _state.update { it.copy(quality = preferred) }
            play(startPositionMs = 0L)
        }
    }

    /**
     * Resolves the video and starts it at [startPositionMs]. The playback token
     * is minted anonymously, like every other one in the app, which is why a
     * subscriber-only video cannot be opened (see [VodFailures]).
     */
    private suspend fun play(startPositionMs: Long) {
        _state.update { it.copy(error = null, errorIsPermanent = false) }
        runCatching { vodRepository.resolvePlayableVod(launch.vodId) }
            .onSuccess { master ->
                twitchPlayer.playVod(master.masterUrl, metadata, startPositionMs)
                _state.update { it.copy(playableUrl = master.masterUrl) }
            }
            .onFailure { e ->
                _state.update { it.copy(error = VodFailures.message(e), errorIsPermanent = VodFailures.isPermanent(e)) }
            }
    }

    /** Jumps to the saved position. A plain seek: the video is already playing. */
    fun resume() {
        val at = _state.value.resumeOfferMs ?: return
        _state.update { it.copy(resumeOfferMs = null) }
        runCatching { twitchPlayer.exoPlayer.seekTo(at) }
    }

    fun startOver() = _state.update { it.copy(resumeOfferMs = null) }

    /** After a failure: a fresh session, picking up where playback stopped. */
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
                _state.update { it.copy(error = STOPPED_PLAYING, errorIsPermanent = false) }
            }
        }
    }

    /** A quality picked in the player: applied now and kept for next time (see [StreamQuality.preferenceFor]). */
    fun setQuality(quality: StreamQuality) {
        twitchPlayer.setQuality(quality)
        _state.update { it.copy(quality = quality) }
        StreamQuality.preferenceFor(quality)?.let { preference ->
            viewModelScope.launch { settingsStore.update { it.copy(preferredQuality = preference) } }
        }
    }

    private fun currentPositionMs(): Long =
        runCatching { twitchPlayer.exoPlayer.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)

    /**
     * Where the viewer is now, or null when there is nothing worth saving yet
     * (still loading, or not a VOD). Main thread only, like every player call.
     */
    private fun snapshot(): WatchProgress? {
        val player = runCatching { twitchPlayer.exoPlayer }.getOrNull() ?: return null
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return null
        val position = player.currentPosition
        if (position <= 0 || !twitchPlayer.isVod) return null
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
        // Teardown lives here, as for live streams: onCleared fires only when
        // the viewer leaves the video, not on rotation or PiP.
        twitchPlayer.stop()
        if (twitchPlayer.onGaveUp === gaveUpListener) twitchPlayer.onGaveUp = null
        backgroundPlayback.onStreamClosed()
    }

    private companion object {
        const val MAX_FRESH_SESSION_RETRIES = 1
        const val SAVE_EVERY_MS = 10_000L
        const val STOPPED_PLAYING =
            "This video stopped playing. PureTV tried to reconnect but couldn't. Check your connection, then try again."
    }
}
