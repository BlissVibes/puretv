package com.puretv.twitch.tv.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretv.twitch.core.adblock.AdBlockEngine
import com.puretv.twitch.core.adblock.AdBlockStatus
import com.puretv.twitch.core.api.DeviceAuth
import com.puretv.twitch.core.api.DevicePollResult
import com.puretv.twitch.core.chat.TwitchChatClient
import com.puretv.twitch.core.chat.batchedEvery
import com.puretv.twitch.core.emotes.EmoteRepository
import com.puretv.twitch.core.follows.FollowRow
import com.puretv.twitch.core.follows.FollowedChannelsSource
import com.puretv.twitch.core.model.AppSettings
import com.puretv.twitch.core.model.ChatEvent
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.model.StreamQuality
import com.puretv.twitch.core.model.ChatTextSize
import com.puretv.twitch.core.model.StreamInfo
import com.puretv.twitch.core.model.GameInfo
import com.puretv.twitch.core.notify.LiveFollowsMonitor
import com.puretv.twitch.core.repository.ChannelRepository
import com.puretv.twitch.core.repository.StreamRepository
import com.puretv.twitch.core.repository.UserRepository
import com.puretv.twitch.core.perf.StreamOpenTiming
import com.puretv.twitch.core.stream.PlayableStreamCache
import com.puretv.twitch.core.stream.StreamFailures
import com.puretv.twitch.tv.data.AppSettingsStore
import com.puretv.twitch.tv.data.TokenRefresher
import com.puretv.twitch.tv.data.db.CachedStreamDao
import com.puretv.twitch.tv.data.db.toCachedStream
import com.puretv.twitch.tv.data.db.toStreamInfo
import io.ktor.client.HttpClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * SECTION 11 / 07: ViewModels backing the TV screens. Functionally
 * identical to the phone app's `ViewModels.kt` (same `core` repositories,
 * same UI-state shape), duplicated rather than shared because Android
 * `ViewModel`/`viewModelScope` and this app's `AppSettingsStore` are
 * platform/app-specific (Section 12.2).
 */

data class HomeUiState(
    val isLoggedIn: Boolean = false,
    val followedLive: List<StreamInfo> = emptyList(),
    val topStreams: List<StreamInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * Landing-screen state. Rebuilt (from the one-shot loader the TV app shipped
 * with) to match the phone app's resilience:
 *   • Cached-first paint: last session's streams show instantly from Room,
 *     so the grid is never empty on a cold, offline, or token-expired start.
 *   • Write-through: every fresh network result is persisted (24h TTL).
 *   • [refresh] is re-runnable and IS wired (resume + periodic, see
 *     `TvHomeScreen`), so "Live Now" repopulates instead of freezing on the
 *     first snapshot. On a failed refresh it refreshes the token once and
 *     retries, recovering the expired-token case that used to 401 silently.
 */
class HomeViewModel(
    private val streamRepository: StreamRepository,
    private val userRepository: UserRepository,
    private val settings: AppSettingsStore,
    private val cachedStreamDao: CachedStreamDao,
    private val tokenRefresher: TokenRefresher,
    private val liveFollows: LiveFollowsMonitor,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    // Once the network returns a fresh top-streams list, stop letting the cached
    // snapshot overwrite it (the cache is only for the instant first paint).
    @Volatile private var freshTopStreamsArrived = false

    // Cancel-and-replace so a resume + timer refresh landing together can't run
    // two overlapping loads that flicker isLoading against each other.
    private var refreshJob: Job? = null

    init {
        // Cached-first: paint last session's streams instantly, before any network.
        viewModelScope.launch {
            cachedStreamDao.observeAll().collect { cached ->
                if (!freshTopStreamsArrived && cached.isNotEmpty()) {
                    _state.update { it.copy(topStreams = cached.map { c -> c.toStreamInfo() }, isLoading = false) }
                }
            }
        }
        // Fresh top streams from the network + write-through to the cache.
        viewModelScope.launch {
            streamRepository.topStreams.collect { top ->
                if (top.isNotEmpty()) {
                    freshTopStreamsArrived = true
                    _state.update { it.copy(topStreams = top, error = null) }
                    runCatching {
                        val now = System.currentTimeMillis()
                        cachedStreamDao.upsertAll(top.map { it.toCachedStream(now) })
                        // Bound the cache so the instant-paint snapshot can't accumulate
                        // thousands of stale rows.
                        cachedStreamDao.pruneStaleEntries(now - CACHE_TTL_MS)
                    }
                }
            }
        }
        // Login flag, recomputed whenever the session changes.
        viewModelScope.launch {
            settings.flow.collect { prefs ->
                _state.update { it.copy(isLoggedIn = prefs.accessToken.isNotBlank()) }
            }
        }
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = it.topStreams.isEmpty(), error = null) }
            val prefs = settings.flow.first()
            // Live status straight from Twitch on every refresh (resume and the 90s
            // poll): one /streams/followed call instead of re-downloading the whole
            // follow list and then checking it. Through the shared monitor, so this
            // refresh is also the go-live banner's check and never doubles it.
            if (prefs.userId.isNotBlank()) {
                runCatching { liveFollows.followedLive(prefs.userId) }
                    .onSuccess { live -> _state.update { it.copy(followedLive = live.sortedByDescending { s -> s.viewerCount }) } }
            }
            val ok = loadTopStreamsResilient()
            _state.update {
                it.copy(
                    isLoading = false,
                    error = if (!ok && it.topStreams.isEmpty()) "Couldn't load. Check your connection and try again." else null,
                )
            }
        }
    }

    /**
     * Refresh top streams; if it fails (a Helix 401 on an expired user token is
     * the common cause) refresh the token once and retry. On success the
     * write-through collector above updates the UI + cache.
     */
    private suspend fun loadTopStreamsResilient(): Boolean {
        if (runCatching { streamRepository.refreshTopStreams() }.isSuccess) return true
        runCatching { tokenRefresher.refreshIfPossible() }
        return runCatching { streamRepository.refreshTopStreams() }.isSuccess
    }

    private companion object {
        // Streams older than this are dropped from the instant-paint cache.
        const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
    }
}

data class TvFollowingUiState(
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val live: List<FollowRow> = emptyList(),
    val offline: List<FollowRow> = emptyList(),
    val errored: Boolean = false,
    /** Why the last load failed, in words (see StreamFailures); null when it worked. */
    val errorReason: String? = null,
)

/**
 * Drives the Following destination (its own nav-rail entry, not just Home's
 * shelf). TV ships no local-pin store the way desktop does, so every row
 * comes straight from the Twitch follow graph and [FollowedChannelsSource.load]
 * always sees an empty pin list.
 *
 * Mirrors the desktop app's `FollowedRailViewModel`: a single in-flight load,
 * cancelled and replaced on [refresh] so a screen re-entry and a manual retry
 * can't race; a fast partial paint the moment live status resolves (avatars
 * may still be loading); and a logged-out reset that clears the profile cache
 * so a different account signing in within the same process never reuses it.
 * The signed-in user id is read the same way [HomeViewModel.refresh] reads
 * it, from `settings.flow.first()`, rather than a synchronous accessor.
 */
class TvFollowingViewModel(
    private val source: FollowedChannelsSource,
    private val settings: AppSettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(TvFollowingUiState())
    val state: StateFlow<TvFollowingUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            settings.flow.collect { prefs ->
                _state.update { it.copy(isLoggedIn = prefs.accessToken.isNotBlank()) }
            }
        }
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadOnce() }
    }

    private suspend fun loadOnce() {
        val userId = settings.flow.first().userId
        if (userId.isBlank()) {
            // Signed out (manually or via token-expiry): drop cached profiles so a
            // different account signing in next within this process never reuses them.
            source.clear()
            _state.update { it.copy(isLoggedIn = false, isLoading = false, live = emptyList(), offline = emptyList()) }
            return
        }
        _state.update { it.copy(isLoggedIn = true, isLoading = it.live.isEmpty() && it.offline.isEmpty()) }
        runCatching {
            source.load(userId, emptyList()) { partial ->
                // Fast partial: live channels are in (avatars may still be loading). Drop
                // the loading state now so the list appears without waiting on avatars.
                _state.update { it.copy(isLoading = false, errored = false, live = partial.live, offline = partial.offline) }
            }
        }
            .onSuccess { full -> _state.update { it.copy(isLoading = false, errored = false, live = full.live, offline = full.offline) } }
            .onFailure { e -> _state.update { it.copy(isLoading = false, errored = true, errorReason = StreamFailures.classify(e).reason()) } }
    }
}

data class BrowseUiState(
    val games: List<GameInfo> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

/**
 * Category grid. Previously a one-shot load that swallowed any failure into an
 * empty list, so a transient network/token failure blanked the grid until a
 * full relaunch (the reported "categories just disappear" bug). Now it keeps the
 * last-known games on failure, surfaces an error + retry only when it has nothing
 * to show, and refreshes the token once before giving up.
 */
class BrowseViewModel(
    private val channelRepository: ChannelRepository,
    private val tokenRefresher: TokenRefresher,
) : ViewModel() {
    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init { refresh() }

    fun retry() = refresh()

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = it.games.isEmpty(), error = null) }
            loadGamesResilient()
                .onSuccess { games ->
                    // ifEmpty: a transient empty response must not wipe a good grid.
                    _state.update { it.copy(games = games.ifEmpty { it.games }, isLoading = false, error = null) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = if (it.games.isEmpty()) (e.message ?: "Couldn't load categories.") else null,
                        )
                    }
                }
        }
    }

    private suspend fun loadGamesResilient(): Result<List<GameInfo>> {
        val first = runCatching { channelRepository.topGames() }
        if (first.isSuccess) return first
        runCatching { tokenRefresher.refreshIfPossible() }
        return runCatching { channelRepository.topGames() }
    }
}

data class CategoryUiState(
    val gameName: String = "",
    val streams: List<StreamInfo> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

/** Streams currently live in a single category (game), reached by tapping a game tile. */
class CategoryViewModel(
    private val gameId: String,
    private val streamRepository: StreamRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CategoryUiState())
    val state: StateFlow<CategoryUiState> = _state.asStateFlow()

    init { load() }

    fun retry() = load()

    private fun load() = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        runCatching { streamRepository.streamsForGame(gameId) }
            .onSuccess { streams ->
                _state.update {
                    it.copy(streams = streams, gameName = streams.firstOrNull()?.gameName ?: it.gameName, isLoading = false)
                }
            }
            .onFailure { e -> _state.update { it.copy(isLoading = false, error = e.message ?: "Couldn't load this category.") } }
    }
}

data class SearchUiState(
    val query: String = "",
    val results: List<com.puretv.twitch.core.api.ChannelSearchResult> = emptyList(),
    val isSearching: Boolean = false,
)

class SearchViewModel(private val channelRepository: ChannelRepository) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    // Cancel-and-replace: every keystroke used to start its own search and none
    // were cancelled, so a slow answer for "xq" could land after, and overwrite,
    // the answer for "xqc". Typing on a remote is slow, so a short pause also
    // keeps each letter from costing a Helix call.
    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.length < 2) {
            _state.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            _state.update { it.copy(isSearching = true) }
            delay(SEARCH_DEBOUNCE_MS)
            val results = runCatching { channelRepository.search(query) }.getOrDefault(emptyList())
            _state.update { it.copy(results = results, isSearching = false) }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}

data class StreamUiState(
    val channel: com.puretv.twitch.core.model.ChannelInfo? = null,
    val streamInfo: StreamInfo? = null,
    val playableUrl: String? = null,
    val adBlockStatus: AdBlockStatus = AdBlockStatus.UNKNOWN,
    val chatMessages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = true,
    /**
     * Why playback could not start, or null while it is fine. Stream resolution
     * is the ONLY path that produces [playableUrl], so when it fails the screen
     * has nothing to render: without this the viewer gets a black rectangle with
     * no audio and no explanation, and chat keeps scrolling beside it (it is a
     * separate coroutine), which reads as "the app works, the stream is gone".
     * Mirrors the phone app's `StreamUiState.playbackError`.
     */
    val playbackError: String? = null,
    /** Settings > Chat text size, as a scale for the chat overlay. */
    val chatTextScale: Float = 1f,
    /**
     * What the player plays at: the saved preference once settings load (null
     * until then, so the screen never applies a placeholder first), then the
     * viewer's pick.
     */
    val quality: StreamQuality? = null,
    /** Settings > Low latency, read with the playable URL so they reach the player together. */
    val lowLatency: Boolean = false,
)

class StreamViewModel(
    private val channelLogin: String,
    private val streamRepository: StreamRepository,
    private val channelRepository: ChannelRepository,
    private val chatClient: TwitchChatClient,
    private val emoteRepository: EmoteRepository,
    private val adBlockEngine: AdBlockEngine,
    private val settingsStore: AppSettingsStore,
    private val playableStreamCache: PlayableStreamCache,
) : ViewModel() {
    private val _state = MutableStateFlow(StreamUiState())
    val state: StateFlow<StreamUiState> = _state.asStateFlow()

    private val openTiming = StreamOpenTiming(channelLogin)

    init {
        adBlockEngine.resetForNewStream()
        // Playback first: resolving the stream does not need the channel info
        // below, so the picture starts while the metadata loads.
        viewModelScope.launch { resolvePlayable() }
        viewModelScope.launch {
            val channelLookup = async { runCatching { channelRepository.getChannel(channelLogin) }.getOrNull() }
            val liveLookup = async {
                runCatching { streamRepository.streamsForChannels(listOf(channelLogin)).firstOrNull() }.getOrNull()
            }
            val channel = channelLookup.await()
            _state.update { it.copy(channel = channel, streamInfo = liveLookup.await(), isLoading = false) }

            runCatching { emoteRepository.loadGlobalEmotes() }
            channel?.let { runCatching { emoteRepository.loadChannelEmotes(it.id, it.login) } }
        }
        viewModelScope.launch {
            adBlockEngine.status.collect { status -> _state.update { it.copy(adBlockStatus = status) } }
        }
        viewModelScope.launch {
            // Section 7.4: TV viewers are overwhelmingly unauthenticated/lean-back;
            // fall back to the anonymous `justinfanNNNN` IRC identity exactly like
            // the phone app when no OAuth session is present (Section 5.1).
            val settings = settingsStore.flow.first()
            _state.update {
                it.copy(
                    chatTextScale = ChatTextSize.from(settings.chatFontSize).scale,
                    quality = StreamQuality.fromSetting(settings.preferredQuality),
                )
            }
            val token = settings.accessToken.takeIf { it.isNotBlank() }
            val username = settings.username.takeIf { it.isNotBlank() }
            chatClient.connect(channelLogin, token, username)
            // A busy chat arrives in batches (about ten a second), each applied in
            // one state update instead of one per message.
            chatClient.events.batchedEvery().collect { batch ->
                val incoming = batch.filterIsInstance<ChatEvent.Message>().map { it.message }
                if (incoming.isNotEmpty()) {
                    _state.update { current -> current.copy(chatMessages = (current.chatMessages + incoming).takeLast(200)) }
                }
            }
        }
    }

    /**
     * Resolve the signed master-playlist URL the player consumes.
     *
     * The failure branch is the point of this function. `runCatching { }` with
     * only an `onSuccess` swallows every cause (a GQL schema change, a blocked
     * usher host, an offline channel, a dead network) and leaves [playableUrl]
     * null, which the screen renders as an indistinguishable black frame. Every
     * one of those needs a different fix, so the message is surfaced verbatim
     * and logged under [TAG] for `adb logcat`.
     */
    private suspend fun resolvePlayable() {
        _state.update { it.copy(playbackError = null) }
        runCatching { playableStreamCache.get(channelLogin) }
            .onSuccess { playable ->
                openTiming.mark("playable_url")
                val lowLatency = settingsStore.flow.first().lowLatencyMode
                _state.update { it.copy(playableUrl = playable.masterUrl, playbackError = null, lowLatency = lowLatency) }
            }
            .onFailure { e ->
                Log.w(TAG, "Stream resolution failed for $channelLogin", e)
                val failure = StreamFailures.classify(e)
                // The technical detail stays on screen in small print: a TV owner
                // cannot run adb, so a photo of this screen is the bug report.
                _state.update {
                    it.copy(
                        playbackError = failure.message() +
                            if (failure.detail.isBlank()) "" else "\n\nDetails: ${failure.detail}",
                    )
                }
            }
    }

    /** Re-run resolution after a failure (OK on the remote), with a fresh session. */
    fun retry() = viewModelScope.launch {
        freshSessionRetries = 0
        playableStreamCache.invalidate(channelLogin)
        resolvePlayable()
    }

    private var freshSessionRetries = 0

    /**
     * The player spent its backed-off retries. Its signed stream URL may simply
     * have expired (a long pause, a network change), so ask Twitch for a fresh
     * session once; if that stream also fails, say so instead of staying black.
     */
    fun onPlaybackGaveUp() {
        viewModelScope.launch {
            if (freshSessionRetries < MAX_FRESH_SESSION_RETRIES) {
                freshSessionRetries++
                playableStreamCache.invalidate(channelLogin)
                resolvePlayable()
            } else {
                _state.update {
                    it.copy(
                        playbackError = "This stream stopped playing. PureTV tried to reconnect but couldn't. " +
                            "Check your connection, then press OK to try again.",
                    )
                }
            }
        }
    }

    /** The player put this stream's first frame on screen (local timing only). */
    fun onFirstFrame() = openTiming.mark("first_frame")

    /** A quality picked in the player: applied by the screen and kept for next time (see [StreamQuality.preferenceFor]). */
    fun setQuality(quality: StreamQuality) {
        _state.update { it.copy(quality = quality) }
        StreamQuality.preferenceFor(quality)?.let { preference ->
            viewModelScope.launch { settingsStore.update { it.copy(preferredQuality = preference) } }
        }
    }

    fun sendChatMessage(text: String) = viewModelScope.launch {
        chatClient.sendMessage(channelLogin, text)
    }

    override fun onCleared() {
        super.onCleared()
        chatClient.disconnect()
    }

    private companion object {
        const val TAG = "StreamViewModel"
        const val MAX_FRESH_SESSION_RETRIES = 1
    }
}

data class ChannelUiState(
    val channel: com.puretv.twitch.core.model.ChannelInfo? = null,
    val isLive: Boolean = false,
    val viewerCount: Long = 0L,
)

class ChannelViewModel(
    private val channelLogin: String,
    private val channelRepository: ChannelRepository,
    private val streamRepository: StreamRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ChannelUiState())
    val state: StateFlow<ChannelUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val channel = runCatching { channelRepository.getChannel(channelLogin) }.getOrNull()
            val stream = runCatching {
                streamRepository.streamsForChannels(listOf(channelLogin)).firstOrNull()
            }.getOrNull()
            _state.update {
                it.copy(channel = channel, isLive = stream != null, viewerCount = stream?.viewerCount?.toLong() ?: 0L)
            }
        }
    }
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val isLoggedIn: Boolean = false,
    val loginUsername: String? = null,
)

class SettingsViewModel(private val settingsStore: AppSettingsStore) : ViewModel() {
    val state: StateFlow<SettingsUiState> = settingsStore.flow.let { flow ->
        val mutable = MutableStateFlow(SettingsUiState())
        viewModelScope.launch {
            flow.collect { prefs ->
                mutable.update {
                    it.copy(
                        settings = prefs,
                        isLoggedIn = prefs.accessToken.isNotBlank(),
                        loginUsername = prefs.username.takeIf { name -> name.isNotBlank() },
                    )
                }
            }
        }
        mutable.asStateFlow()
    }

    fun setChatTextSize(size: ChatTextSize) = viewModelScope.launch {
        settingsStore.update { it.copy(chatFontSize = size.sp) }
    }

    fun setPreferredQuality(quality: com.puretv.twitch.core.model.StreamQuality) = viewModelScope.launch {
        settingsStore.update { it.copy(preferredQuality = quality.name.lowercase()) }
    }

    fun setAdBlockEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsStore.update { it.copy(adBlockEnabled = enabled) }
    }

    fun setLowLatency(enabled: Boolean) = viewModelScope.launch {
        settingsStore.update { it.copy(lowLatencyMode = enabled) }
    }

    fun setLiveNotifications(enabled: Boolean) = viewModelScope.launch {
        settingsStore.update { it.copy(liveNotifications = enabled) }
    }

    fun setAdBlockStrategy(strategy: com.puretv.twitch.core.adblock.AdBlockStrategy) = viewModelScope.launch {
        settingsStore.update { it.copy(adBlockStrategy = strategy.name.lowercase()) }
    }

    fun setProxyUrl(url: String) = viewModelScope.launch {
        settingsStore.update { it.copy(customProxyUrl = url) }
    }

    fun logOut() = settingsStore.clearSession()
}

data class LoginUiState(
    val isAuthenticating: Boolean = false,
    val userCode: String? = null,
    val verificationUri: String? = null,
    val error: String? = null,
    val isLoggedIn: Boolean = false,
)

/**
 * SECTION 03.2 / 07: Twitch Device Code Grant flow, the correct 10-foot login.
 *
 * Twitch does not accept custom-scheme redirect URIs, so the authorization-code
 * + `puretv-twitch://auth` redirect approach cannot work on a TV (there is no
 * browser round-trip back into the app, and no keyboard to type credentials).
 * The device flow needs no redirect: [TvLoginScreen] shows [userCode] and
 * points the viewer at [verificationUri] (twitch.tv/activate) to enter it on a
 * phone/computer, while this VM polls until Twitch reports the code authorized,
 * then persists the session. Same flow the phone and desktop apps use.
 */
class LoginViewModel(
    private val httpClient: HttpClient,
    private val settingsStore: AppSettingsStore,
    private val userRepository: UserRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private var loginJob: Job? = null

    fun beginLogin() {
        if (loginJob?.isActive == true) return
        loginJob = viewModelScope.launch {
            _state.value = LoginUiState(isAuthenticating = true)

            val device = runCatching { DeviceAuth.requestDeviceCode(httpClient) }.getOrElse { e ->
                _state.update { it.copy(isAuthenticating = false, error = e.message ?: "Could not start sign-in.") }
                return@launch
            }
            _state.update { it.copy(userCode = device.userCode, verificationUri = device.verificationUri) }

            var intervalMs = device.intervalSeconds.coerceAtLeast(1) * 1_000
            val expiresMs = device.expiresInSeconds.coerceAtLeast(1) * 1_000
            var elapsedMs = 0L
            while (elapsedMs < expiresMs) {
                delay(intervalMs)
                elapsedMs += intervalMs
                when (val result = runCatching { DeviceAuth.pollOnce(httpClient, device.deviceCode) }.getOrNull()) {
                    is DevicePollResult.Success -> {
                        val token = result.token
                        // Persist token-first so the API client is authenticated, then
                        // look up the account identity and persist it so chat sends
                        // under the right name and Settings shows the username.
                        settingsStore.setSession(accessToken = token.accessToken, refreshToken = token.refreshToken)
                        val me = runCatching { userRepository.getCurrentUser() }.getOrNull()
                        if (me != null) {
                            settingsStore.setSession(
                                accessToken = token.accessToken,
                                refreshToken = token.refreshToken,
                                username = me.login,
                                userId = me.id,
                            )
                        }
                        _state.update { it.copy(isAuthenticating = false, isLoggedIn = true) }
                        return@launch
                    }
                    is DevicePollResult.SlowDown -> intervalMs += 5_000
                    is DevicePollResult.Expired -> {
                        _state.update {
                            it.copy(isAuthenticating = false, userCode = null, verificationUri = null, error = "Sign-in expired, please try again.")
                        }
                        return@launch
                    }
                    // Pending, or a transient null from a network hiccup: keep polling.
                    else -> {}
                }
            }
            _state.update {
                it.copy(isAuthenticating = false, userCode = null, verificationUri = null, error = "Sign-in timed out, please try again.")
            }
        }
    }

    override fun onCleared() {
        loginJob?.cancel()
        super.onCleared()
    }
}
