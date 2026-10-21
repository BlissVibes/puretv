package com.puretv.twitch.desktop.ui

import com.puretv.twitch.core.adblock.AdBlockEngine
import com.puretv.twitch.core.adblock.AdBlockStatus
import com.puretv.twitch.core.api.TwitchApiClient
import com.puretv.twitch.core.chat.BadgeRepository
import com.puretv.twitch.core.chat.TwitchChatClient
import com.puretv.twitch.core.emotes.EmoteRepository
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.model.StreamQuality
import com.puretv.twitch.core.repository.ChannelRepository
import com.puretv.twitch.core.stream.PlayableStreamCache
import com.puretv.twitch.core.stream.Failure
import com.puretv.twitch.core.stream.FailureKind
import com.puretv.twitch.desktop.data.DesktopSettingsStore
import com.puretv.twitch.desktop.notify.DesktopLiveNotifier
import com.puretv.twitch.desktop.player.DEFAULT_VOLUME
import com.puretv.twitch.desktop.player.DesktopPlayerProvider
import com.puretv.twitch.desktop.player.LocalStreamProxy
import com.puretv.twitch.desktop.player.PlaybackStallWatchdog
import com.puretv.twitch.desktop.player.ProxyUnavailableException
import com.puretv.twitch.desktop.player.TILE_AUDIO_OUTPUT
import com.puretv.twitch.desktop.player.TileQuality
import com.puretv.twitch.desktop.player.VlcEngine
import com.puretv.twitch.desktop.player.VlcPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a tile shows besides its video. */
data class TileInfo(
    val displayName: String,
    val avatarUrl: String? = null,
    /** Null until the tile's player exists (libVLC is built off the UI thread). */
    val player: VlcPlayer? = null,
    val adBlockStatus: AdBlockStatus = AdBlockStatus.UNKNOWN,
    val isBuffering: Boolean = true,
    /** Why this tile cannot play, in words; null while it plays or is still trying. */
    val message: String? = null,
)

data class MultiViewUiState(
    val layout: MultiViewState = MultiViewState(),
    val info: Map<Int, TileInfo> = emptyMap(),
    /** Set when the local proxy cannot start, which no tile can work around. */
    val fatalError: String? = null,
)

/**
 * The multi-stream screen: up to four live channels at once, one of them active.
 *
 * Every tile is its own [VlcPlayer] made from the shared [VlcEngine], never the
 * single-stream player, so leaving the normal stream screen can never stop a tile
 * (and the mpv engine is not involved). All tiles keep playing, each with its own
 * volume and mute. By default only the active tile is heard; the viewer can choose to
 * hear every unmuted tile instead. Either way only the players' volume changes, so
 * moving the sound never reloads a stream. A tile's quality depends
 * on how many tiles there are (see [TileQuality]); when that changes, only the tiles
 * whose target changed reload.
 *
 * Each tile gets the same recovery as the single stream screen: a failure is
 * classified, final ones are explained, the others retry after the Wave 2 delays,
 * and a stalled picture is reloaded with a fresh session.
 */
class MultiViewViewModel(
    initialLogin: String,
    private val engine: VlcEngine,
    private val adBlockEngine: AdBlockEngine,
    private val localStreamProxy: LocalStreamProxy,
    private val playableStreamCache: PlayableStreamCache,
    private val settingsStore: DesktopSettingsStore,
    private val channelRepository: ChannelRepository,
    chatClient: TwitchChatClient,
    emoteRepository: EmoteRepository,
    badgeRepository: BadgeRepository,
    apiClient: TwitchApiClient,
    private val players: DesktopPlayerProvider,
    private val notifier: DesktopLiveNotifier,
) : DesktopViewModel() {

    // New tiles start at the volume the viewer last had on the normal stream screen, when
    // that player exists and is not at zero; the settings hold no volume of their own.
    private val startVolume: Int = run {
        var volume = DEFAULT_VOLUME
        players.ifCreated { p -> p.status.value.volume.takeIf { it > 0 }?.let { volume = it } }
        volume
    }

    private val _state = MutableStateFlow(
        MultiViewUiState(
            layout = MultiViewState(listenAll = rememberedListenAll).add(initialLogin, startVolume),
        ),
    )
    val state: StateFlow<MultiViewUiState> = _state.asStateFlow()

    // One chat connection for the whole screen, following the active tile.
    private val chat = MultiChatSession(
        chatClient, emoteRepository, badgeRepository, channelRepository, apiClient, settingsStore, scope,
    )
    val chatState: StateFlow<MultiChatState> = chat.state
    fun startReply(message: ChatMessage) = chat.startReply(message)
    fun cancelReply() = chat.cancelReply()
    fun sendChat(text: String) = chat.send(text)

    /** What runs behind one tile. Touched on several coroutines, so only under [lock]. */
    private class Runtime(
        val id: Int,
        val login: String,
        val watchdog: PlaybackStallWatchdog = PlaybackStallWatchdog(),
    ) {
        @Volatile var player: VlcPlayer? = null
        @Volatile var autoRetries = 0
        @Volatile var playingHeight = 0
        @Volatile var closed = false
        private val freed = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Stops and releases [player] exactly once, whichever of close and setup gets here first. */
        fun freeOnce(player: VlcPlayer) {
            if (!freed.compareAndSet(false, true)) return
            player.stop()
            player.release()
        }
        val jobs = ArrayList<Job>()
    }

    private val lock = Any()
    private val audioLock = Any()
    // Guards the go-live skip list so a late update cannot land after the screen closed.
    private val watchLock = Any()
    private var cleared = false
    private val runtimes = HashMap<Int, Runtime>()

    // The proxy start is shared: every tile waits for the same attempt.
    private val proxyReady = MutableStateFlow<Boolean?>(null)

    init {
        scope.launch { startProxy() }
        _state.value.layout.tiles.forEach { openRuntime(it) }
        // Go-live toasts skip every channel on screen, not only the one App knows about.
        scope.launch {
            _state.map { s -> s.layout.tiles.mapTo(HashSet()) { it.login } }.distinctUntilChanged()
                .collect { tiles -> synchronized(watchLock) { if (!cleared) notifier.alsoWatching = tiles } }
        }
        // Chat belongs to the active tile: when the sound moves, so does the chat.
        scope.launch {
            _state.map { it.layout.active?.login }.distinctUntilChanged().collect { chat.switchTo(it) }
        }
        // One loop watches every tile for a picture that froze while still "playing".
        scope.launch {
            while (true) {
                delay(STALL_CHECK_MS)
                val now = System.currentTimeMillis()
                val live = synchronized(lock) { runtimes.values.toList() }
                for (rt in live) {
                    val player = rt.player ?: continue
                    if (rt.watchdog.sample(player.status.value, now)) {
                        // A stalled session should not be reused: mint a fresh one.
                        playableStreamCache.invalidate(rt.login)
                        startPlayback(rt)
                    }
                }
            }
        }
    }

    private suspend fun startProxy() {
        val started = runCatching { localStreamProxy.start() }
        if (started.isFailure) {
            _state.update {
                it.copy(
                    fatalError = (started.exceptionOrNull() as? ProxyUnavailableException)?.message
                        ?: "PureTV's local video proxy could not start. Try closing other PureTV windows and reopening multi-stream.",
                )
            }
        }
        proxyReady.value = started.isSuccess
    }

    // ---- Tiles ------------------------------------------------------------------

    /** Adds [login] as a tile; false when it is already shown, blank, or the screen is full. */
    fun addChannel(login: String): Boolean {
        val before = _state.value.layout
        val after = before.add(login, startVolume)
        if (after === before) return false
        _state.update { it.copy(layout = after) }
        val tile = after.tiles.last()
        openRuntime(tile)
        retargetQuality()
        applyAudio()
        return true
    }

    /** Closes tile [id], stopping and freeing its player. The tiles left are returned. */
    fun closeTile(id: Int): List<MultiTile> {
        val after = _state.value.layout.remove(id)
        _state.update { it.copy(layout = after, info = it.info - id) }
        closeRuntime(id)
        retargetQuality()
        applyAudio()
        return after.tiles
    }

    /** Selects tile [id]: the one you hear (unless listening to all) and the keys act on. Nothing reloads. */
    fun setActive(id: Int) {
        val before = _state.value.layout
        val after = before.setActive(id)
        if (after === before) return
        _state.update { it.copy(layout = after) }
        applyAudio()
    }

    fun swap(a: Int, b: Int) = _state.update { it.copy(layout = it.layout.swap(a, b)) }

    /** Moves tile [id] one place earlier (-1) or later (1) in the grid. Only the order changes: no player is touched. */
    fun move(id: Int, delta: Int) = _state.update { it.copy(layout = it.layout.move(id, delta)) }

    /** Up or Down on the keyboard: volume for the selected tile, as on the normal stream screen. */
    fun nudgeVolume(up: Boolean) = changeLayout { l -> l.activeId?.let { l.nudge(it, up) } ?: l }

    /** The volume bar of tile [id]. */
    fun setVolume(id: Int, volume: Int) = changeLayout { it.selecting(id).withVolume(id, volume) }

    /** The speaker button of tile [id]. */
    fun toggleMute(id: Int) = changeLayout { it.selecting(id).toggleMute(id) }

    /** M on the keyboard: mutes or unmutes the selected tile. */
    fun toggleActiveMute() = changeLayout { l -> l.activeId?.let { l.toggleMute(it) } ?: l }

    /** "Listen to": only the selected tile, or every tile at its own volume. Remembered for this session. */
    fun setListenAll(all: Boolean) {
        rememberedListenAll = all
        changeLayout { it.withListenAll(all) }
    }

    private fun changeLayout(change: (MultiViewState) -> MultiViewState) {
        val before = _state.value.layout
        val after = change(before)
        if (after === before) return
        _state.update { it.copy(layout = after) }
        applyAudio()
    }

    // ---- Runtimes ---------------------------------------------------------------

    private fun openRuntime(tile: MultiTile) {
        val rt = Runtime(tile.id, tile.login)
        synchronized(lock) { runtimes[tile.id] = rt }
        _state.update { it.copy(info = it.info + (tile.id to TileInfo(displayName = tile.login))) }
        val setup = scope.launch {
            // libVLC is built on first use and its plugin scan is slow: not on the UI thread.
            val player = VlcPlayer(engine, audioOutput = TILE_AUDIO_OUTPUT)
            if (rt.closed) {
                rt.freeOnce(player)
                return@launch
            }
            rt.player = player
            // A close that landed between the check above and this assignment saw no
            // player to free, so free it here (a no-op if the close got there first).
            if (rt.closed) {
                rt.freeOnce(player)
                return@launch
            }
            updateInfo(rt.id) {
                it.copy(
                    player = player,
                    message = if (player.isAvailable) null else player.status.value.error,
                )
            }
            if (!player.isAvailable) return@launch
            applyAudio()
            if (!rt.closed) adBlockEngine.resetForNewStream(rt.login)
            // Wait for the shared proxy start before the first request goes out.
            proxyReady.first { it != null }
            if (proxyReady.value != true || rt.closed) return@launch
            launchWatchers(rt, player)
            startPlayback(rt)
        }
        synchronized(lock) { rt.jobs += setup }
        // Names and avatars are cosmetic: load them without holding up the picture.
        synchronized(lock) {
            rt.jobs += scope.launch {
                val channel = runCatching { channelRepository.getChannel(tile.login) }.getOrNull()
                if (channel != null) {
                    updateInfo(rt.id) {
                        it.copy(displayName = channel.displayName.ifBlank { tile.login }, avatarUrl = channel.profileImageUrl)
                    }
                }
            }
        }
    }

    private fun launchWatchers(rt: Runtime, player: VlcPlayer) {
        synchronized(lock) {
            rt.jobs += scope.launch {
                adBlockEngine.statusFor(rt.login).collect { status -> updateInfo(rt.id) { it.copy(adBlockStatus = status) } }
            }
            rt.jobs += scope.launch {
                player.status.map { it.isBuffering to it.isPlaying }.distinctUntilChanged().collect { (buffering, playing) ->
                    updateInfo(rt.id) { it.copy(isBuffering = buffering && !playing, message = if (playing) null else it.message) }
                }
            }
            rt.jobs += scope.launch {
                // Same recovery as the single stream screen: ask the proxy what really
                // happened, explain a stream that cannot play, and retry the others with
                // a fresh session after a growing pause.
                player.status.map { it.error to it.isPlaying }.distinctUntilChanged().collect { (error, playing) ->
                    if (playing) rt.autoRetries = 0
                    if (error == null || rt.closed) return@collect
                    val failure = localStreamProxy.failureFor(rt.login)
                    when {
                        failure != null && failure.kind in FINAL_FAILURES ->
                            updateInfo(rt.id) { it.copy(message = failure.message()) }
                        rt.autoRetries < AUTO_RETRY_DELAYS_MS.size -> {
                            delay(AUTO_RETRY_DELAYS_MS[rt.autoRetries])
                            rt.autoRetries++
                            playableStreamCache.invalidate(rt.login)
                            rt.watchdog.reset()
                            if (!rt.closed) startPlayback(rt)
                        }
                        else -> updateInfo(rt.id) {
                            it.copy(message = (failure ?: Failure(FailureKind.UNKNOWN, error)).message())
                        }
                    }
                }
            }
        }
    }

    /** Every (re)start of a tile goes through here, so retries keep the tile's quality and low latency. */
    private fun startPlayback(rt: Runtime) {
        val player = rt.player ?: return
        val height = targetHeight()
        val lowLatency = settingsStore.settings.value.lowLatencyMode
        // Under the lock so a close cannot slip in between the check and the play:
        // play() only queues work on the UI thread, and close queues stop and release
        // after it, so a closed tile is never played again.
        synchronized(lock) {
            if (rt.closed) return
            rt.playingHeight = height
            player.play(LocalStreamProxy.tileStreamUrl(rt.login, height), lowLatency = lowLatency)
        }
        applyAudio()
    }

    private fun targetHeight(): Int {
        val preference = StreamQuality.fromSetting(settingsStore.settings.value.preferredQuality)
        return TileQuality.maxHeight(_state.value.layout.qualityCap(preference))
    }

    /** After a tile joins or leaves: reload only the tiles whose quality target changed. */
    private fun retargetQuality() {
        val height = targetHeight()
        val changed = synchronized(lock) { runtimes.values.filter { it.player != null && it.playingHeight != 0 && it.playingHeight != height } }
        changed.forEach {
            it.watchdog.reset()
            startPlayback(it)
        }
    }

    /**
     * Gives every tile's player the sound its tile asks for: an audible tile plays at its
     * own volume, every other one is muted. Safe to repeat: the player keeps the setting
     * across reloads and re-asserts it once its audio output exists (see VlcPlayer).
     */
    private fun applyAudio() {
        // One pass at a time, reading the layout inside it, so overlapping calls from the
        // UI and the tile coroutines always end on the newest layout.
        synchronized(audioLock) {
            val layout = _state.value.layout
            val players = synchronized(lock) {
                runtimes.values.filterNot { it.closed }.mapNotNull { rt -> rt.player?.let { rt.id to it } }
            }
            for ((id, player) in players) {
                val tile = layout.tiles.firstOrNull { it.id == id } ?: continue
                if (layout.isAudible(id)) {
                    // Volume first: it also lifts the mute, so there is no blip at the old level.
                    player.setVolume(tile.volume)
                    player.setMuted(false)
                } else {
                    player.setMuted(true)
                }
            }
        }
    }

    private fun updateInfo(id: Int, change: (TileInfo) -> TileInfo) {
        _state.update { s ->
            val current = s.info[id] ?: return@update s
            s.copy(info = s.info + (id to change(current)))
        }
    }

    private fun closeRuntime(id: Int) {
        val rt = synchronized(lock) {
            runtimes.remove(id)?.also { it.closed = true }
        } ?: return
        synchronized(lock) { rt.jobs.toList() }.forEach { it.cancel() }
        rt.player?.let { rt.freeOnce(it) }
        adBlockEngine.forgetChannel(rt.login)
    }

    /** Stops and frees every tile, so leaving the screen leaves nothing playing or decoding. */
    override fun onCleared() {
        chat.close()
        synchronized(watchLock) {
            cleared = true
            notifier.alsoWatching = emptySet()
        }
        val ids = synchronized(lock) { runtimes.keys.toList() }
        ids.forEach { closeRuntime(it) }
    }

    private companion object {
        const val STALL_CHECK_MS = 2_000L

        // The "Listen to" choice outlives one visit to the screen, but not the app.
        @Volatile var rememberedListenAll = false
    }
}
