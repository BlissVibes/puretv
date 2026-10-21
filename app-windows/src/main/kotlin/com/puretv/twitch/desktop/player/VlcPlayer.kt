package com.puretv.twitch.desktop.player

import com.puretv.twitch.core.stream.LiveLatency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.State
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.awt.Component
import javax.swing.SwingUtilities

/**
 * SECTION 08.2 [CRITICAL] — wraps VLC via VLCJ for video playback.
 *
 * VLC resolution order:
 *   1. Bundled copy — when running as a packaged distributable, looks for
 *      `vlc/libvlc.dll` inside `compose.application.resources.dir` (populated
 *      by `gradle :app-windows:bundleVlc` before packaging).
 *   2. System installation — falls back to auto-discovery (standard VLC
 *      install paths, jna.library.path, PATH) for development builds.
 *
 * [VlcPlayer] is a Koin singleton so the same playback session persists across
 * navigation, mirroring `TwitchPlayer`/`TvPlayer` on the other platforms.
 *
 * THREADING — VLCJ callbacks fire on internal VLC threads; anything that
 * touches Swing/AWT (attaching the video surface, resizing) MUST be marshalled
 * onto the EDT via [SwingUtilities.invokeLater]. [PlayerStatus] updates are
 * safe to publish from any thread since [MutableStateFlow] is thread-safe.
 */
class VlcPlayer(
    // The shared libVLC instance (see VlcEngine), which owns the bundled-VLC lookup,
    // the cache arguments and the audio normalizer. The single-stream player and
    // every multi-stream tile each make their own media player from it.
    private val engine: VlcEngine,
    // A named libVLC audio output for this player alone, or null for VLC's default.
    // See [TILE_AUDIO_OUTPUT]: the default output shares one volume across players.
    audioOutput: String? = null,
    // True for a multi-stream tile, whose video can move to another window (see
    // PoppedStreamWindow). libVLC binds its surface when playback starts, so a surface set
    // while playing is ignored: the same stream is started again on the same player
    // instead, which costs a second or two of rebuffering and nothing else.
    private val replayOnNewSurface: Boolean = false,
) : DesktopPlayer {

    private val mediaPlayer: EmbeddedMediaPlayer? = engine.newEmbeddedPlayer()?.also { mp ->
        if (audioOutput != null) runCatching { mp.audio().setOutput(audioOutput) }
    }

    private val _status = MutableStateFlow(
        if (mediaPlayer == null) PlayerStatus(
            error = when {
                System.getProperty("compose.application.resources.dir") != null ->
                    "VLC bundle missing — please reinstall PureTV for Twitch."
                else ->
                    "VLC not found. Install VLC from https://videolan.org/vlc/ and restart PureTV."
            },
        ) else PlayerStatus(),
    )
    override val status: StateFlow<PlayerStatus> = _status.asStateFlow()

    override val isAvailable: Boolean get() = mediaPlayer != null

    private var currentUrl: String? = null

    // Per-media options for currentUrl, replayed whenever it is (re)started.
    @Volatile private var currentOptions: Array<String> = emptyArray()

    // Remembers the pre-mute level so unmuting restores it. Touched from the
    // EDT (slider) and VLC callback threads, hence @Volatile.
    @Volatile private var preMuteVolume: Int = DEFAULT_VOLUME

    // Volume and mute are read-modify-write on both the status and libVLC, and arrive from
    // the UI thread, tile coroutines and VLC's event thread: one at a time, so a stale
    // write (a late timeChanged) can never undo a newer change.
    private val audioLock = Any()

    init {
        mediaPlayer?.events()?.addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) {
                _status.update { it.copy(isPlaying = true, isBuffering = false, error = null) }
                // Assert our volume on playback start so libVLC stops adopting its
                // own default (~30-50% perceived) until the user first drags the slider.
                // The audio output usually does not exist yet at this moment, so this
                // can be dropped; timeChanged below repeats it until it sticks.
                syncAudio(mediaPlayer)
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                _status.update { it.copy(isPlaying = false) }
            }

            override fun stopped(mediaPlayer: MediaPlayer) {
                _status.update { it.copy(isPlaying = false, isBuffering = false) }
            }

            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                _status.update { it.copy(isBuffering = newCache < 100f) }
            }

            override fun error(mediaPlayer: MediaPlayer) {
                _status.update { it.copy(isPlaying = false, isBuffering = false, error = "Playback error — VLC reported a fault") }
            }

            override fun mediaPlayerReady(mediaPlayer: MediaPlayer) {
                _status.update { it.copy(isReady = true) }
            }

            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                _status.update { it.copy(positionMs = newTime) }
                // libVLC forgets a volume set before its audio output exists, and starts
                // that output muted: measured on a live tile, VLC read back volume 0 and
                // muted while this player held 60 and unmuted, so the tile was silent.
                // Every new media gets a new output, so this also covers each reload.
                syncAudio(mediaPlayer)
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                _status.update { it.copy(durationMs = newLength) }
            }

            override fun seekableChanged(mediaPlayer: MediaPlayer, newSeekable: Int) {
                _status.update { it.copy(isSeekable = newSeekable != 0) }
            }
        })
    }

    /**
     * Attaches the embedded player's video surface to an AWT [Component]
     * inside a Compose `SwingPanel` (see `VlcPlayerView.kt`). Must be called
     * on the EDT — [SwingPanel]'s factory lambda already runs there.
     */
    // The VLC surface attach can be triggered multiple times by Compose
    // recomposition or HierarchyListener fires. Re-setting the same surface
    // is benign in VLCJ, but tracking the bound component lets us short-circuit
    // and avoid spurious libvlc churn.
    @Volatile private var attachedComponent: Component? = null
    private var hadSurface = false

    override fun attachToPanel(panel: Component) {
        check(SwingUtilities.isEventDispatchThread()) { "attachToPanel must be called on the Swing EDT" }
        if (attachedComponent === panel) return
        val mp = mediaPlayer ?: return
        // VLCJ 4.x: ComponentIdVideoSurface takes a native window handle (long),
        // not an AWT Component. Use the engine's videoSurfaces() factory to mint
        // a platform-appropriate surface (Win32/X11/macOS) bound to this Canvas.
        //
        // CRITICAL: VLCJ doesn't actually bind to the native handle here — it
        // does so lazily inside media().play() via onBeforePlay → attachVideoSurface,
        // which re-checks isDisplayable at THAT moment. So we still gate on
        // isDisplayable here to avoid setting a stale surface, AND the play()
        // call below re-checks just before VLC dereferences the handle.
        if (!panel.isDisplayable) return
        mp.videoSurface().set(engine.newVideoSurface(panel))
        attachedComponent = panel
        val moved = hadSurface && replayOnNewSurface
        hadSurface = true
        // Drain any URL that was queued by `play()` before we had a surface to
        // render into — common on first stream open, where ViewModel.init
        // calls play() one EDT-tick before SwingPanel's HierarchyListener fires.
        currentUrl?.let { url ->
            if (moved || mp.status().state() != State.PLAYING) {
                if (moved) _status.update { it.copy(isBuffering = true) }
                // A past video moved to a new window carries on from where it is, not from where it began.
                runCatching { mp.media().play(url, *if (moved) optionsToReplay() else currentOptions) }
                    .onFailure { e -> _status.update { it.copy(error = "Player attach race: ${e.message}") } }
            }
        }
    }

    /**
     * No-op for VLC. VLCJ tolerates its embedded surface's native peer being
     * destroyed and re-binds to a fresh Canvas on the next [attachToPanel] (a new
     * component fails the `attachedComponent` short-circuit). The interface
     * requires this hook for mpv, whose set-once `wid` cannot survive HWND teardown.
     */
    override fun detachFromPanel() { /* VLC re-attaches; nothing to release early. */ }

    /**
     * Starts playback of [streamUrl] — typically the local proxy's `/stream?...` URL (Section 8.3).
     *
     * If the video surface hasn't been attached yet (Compose hasn't laid out
     * the SwingPanel that hosts our Canvas), we cache the URL and defer the
     * actual `media().play()` call until [attachToPanel] runs. Without this,
     * VLC starts decoding into a 0-handle surface and the user sees a black
     * pane forever — even after the surface eventually binds, libvlc doesn't
     * re-target an already-running session.
     */
    override fun play(streamUrl: String, lowLatency: Boolean) {
        // VLC starts a live HLS stream 15 s back from the end of the playlist by
        // default; low latency starts it LiveLatency.VLC_LIVE_DELAY_MS back.
        val options = if (lowLatency) arrayOf(":adaptive-livedelay=${LiveLatency.VLC_LIVE_DELAY_MS}") else emptyArray()
        isVod = false
        start(streamUrl, options)
    }

    /**
     * Plays a past video from [startMs]. Unlike a live stream it keeps its place: when the
     * video moves to another window, or is played again after it stopped, it starts from
     * where it was (see [optionsToReplay]) instead of from [startMs] again.
     */
    fun playVod(videoUrl: String, startMs: Long) {
        isVod = true
        _status.update { it.copy(positionMs = startMs.coerceAtLeast(0)) }
        start(videoUrl, VodPlayback.startOptions(startMs))
    }

    // True once a past video was started: its reloads resume at the current position.
    @Volatile private var isVod = false

    /** The per-media options for starting [currentUrl] again: a live stream as it was, a past video where it is now. */
    private fun optionsToReplay(): Array<String> =
        if (isVod) VodPlayback.startOptions(_status.value.positionMs) else currentOptions

    private fun start(streamUrl: String, options: Array<String>) {
        val mp = mediaPlayer ?: return
        currentUrl = streamUrl
        currentOptions = options
        _status.update { it.copy(isBuffering = true, error = null) }
        val component = attachedComponent ?: return
        // else: attachToPanel will pick up currentUrl and start playback once
        // the surface is bound. See its tail.
        SwingUtilities.invokeLater {
            // VLCJ's ComponentVideoSurface.attach() — invoked transitively by
            // media().play() via EmbeddedMediaPlayer.onBeforePlay() — re-checks
            // isDisplayable at play-time, not just when we set the surface. The
            // Canvas can briefly lose its native peer during Compose recomposition
            // (SwingPanel relayout) between attachToPanel returning and this
            // invokeLater firing. If we don't re-check we get
            // "IllegalStateException: The video surface component must be displayable"
            // thrown into the AWT-EventQueue thread, which kills the EDT.
            if (!component.isDisplayable) {
                attachedComponent = null  // force re-attach when HierarchyListener fires next
                return@invokeLater
            }
            runCatching { mp.media().play(streamUrl, *options) }
                .onFailure { e ->
                    _status.update {
                        it.copy(isPlaying = false, isBuffering = false, error = "Playback start failed: ${e.message}")
                    }
                }
        }
    }

    override fun pause() {
        val mp = mediaPlayer ?: return
        SwingUtilities.invokeLater {
            if (mp.status().state() == State.PLAYING) mp.controls().pause()
        }
    }

    /** Seek to an absolute position. No-op for non-seekable (live) media. */
    override fun seekTo(positionMs: Long) {
        val mp = mediaPlayer ?: return
        SwingUtilities.invokeLater {
            if (mp.status().isSeekable) mp.controls().setTime(positionMs.coerceAtLeast(0))
        }
    }

    override fun resume() {
        val mp = mediaPlayer ?: return
        SwingUtilities.invokeLater {
            if (mp.status().state() == State.PAUSED) mp.controls().play()
            else currentUrl?.let { url ->
                // A video that ended plays again from its start; one that stopped, from where it was.
                val options = if (isVod) {
                    VodPlayback.startOptions(VodPlayback.replayStartMs(_status.value.positionMs, _status.value.durationMs))
                } else currentOptions
                mp.media().play(url, *options)
            }
        }
    }

    override fun togglePlayPause() {
        if (_status.value.isPlaying) pause() else resume()
    }

    override fun stop() {
        val mp = mediaPlayer ?: return
        SwingUtilities.invokeLater {
            mp.controls().stop()
            currentUrl = null
        }
    }

    /** [volume] is 0–100, matching VLC's native scale (Section 8.4 volume slider). */
    override fun setVolume(volume: Int) = synchronized(audioLock) {
        val mp = mediaPlayer ?: return@synchronized
        val next = applyVolumeChange(VolumeState(_status.value.volume, _status.value.isMuted, preMuteVolume), volume)
        preMuteVolume = next.preMute
        // INSTANT volume — do NOT marshal through SwingUtilities.invokeLater.
        // The slider's onValueChange already runs on the EDT, so invokeLater only
        // *re-queued* this work behind whatever AWT repaint/resize tasks the
        // heavyweight VLC video surface had pending — a backlog that grew to the
        // ~2s lag users reported while a stream was playing. Neither call needs
        // the EDT: libvlc_audio_set_volume is thread-safe, and MutableStateFlow
        // updates are safe from any thread (see class header). Publishing the UI
        // state first keeps the slider glued to the cursor.
        _status.update { it.copy(volume = next.volume, isMuted = next.isMuted) }
        syncAudio(mp)
    }

    /** Toggles mute, remembering the pre-mute level so unmute restores it. */
    override fun toggleMute() = synchronized(audioLock) {
        val mp = mediaPlayer ?: return@synchronized
        val next = applyMuteToggle(VolumeState(_status.value.volume, _status.value.isMuted, preMuteVolume))
        preMuteVolume = next.preMute
        _status.update { it.copy(volume = next.volume, isMuted = next.isMuted) }
        syncAudio(mp)
    }

    override fun release() {
        val mp = mediaPlayer ?: return
        // Only this player's own media player; libVLC belongs to the engine.
        SwingUtilities.invokeLater {
            mp.controls().stop()
            mp.release()
        }
    }

    /**
     * Mutes or unmutes without touching the remembered volume. Multi-stream keeps
     * every tile playing and only the active one audible, so switching which tile
     * you hear never reloads a stream.
     */
    fun setMuted(muted: Boolean) = synchronized(audioLock) {
        if (_status.value.isMuted != muted) toggleMute()
    }

    /**
     * Makes libVLC's volume and mute agree with [status]: a muted player plays at
     * volume 0, an unmuted one at its volume with libVLC's own mute flag off. Reads
     * first, so the common case (already in agreement) changes nothing.
     */
    private fun syncAudio(mp: MediaPlayer) = synchronized(audioLock) {
        val s = _status.value
        val want = if (s.isMuted) 0 else s.volume
        runCatching {
            val audio = mp.audio()
            if (audio.volume() != want) audio.setVolume(want)
            if (!s.isMuted && audio.isMute) audio.setMute(false)
        }
    }

}
