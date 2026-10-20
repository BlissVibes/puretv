package com.puretv.twitch.desktop.di

import com.puretv.twitch.desktop.auth.DesktopOAuthManager
import com.puretv.twitch.desktop.data.DesktopSettingsStore
import com.puretv.twitch.desktop.data.FollowStore
import com.puretv.twitch.desktop.data.LiveNotifyFileStore
import com.puretv.twitch.desktop.notify.DesktopLiveNotifier
import com.puretv.twitch.desktop.ui.NavigationRequests
import com.puretv.twitch.core.api.TwitchApiClient
import com.puretv.twitch.core.notify.LiveFollowsMonitor
import androidx.compose.ui.window.TrayState
import com.puretv.twitch.desktop.data.ViewerHistoryStore
import com.puretv.twitch.desktop.data.WatchProgressStore
import com.puretv.twitch.core.audio.AudioNormalizerProfile
import com.puretv.twitch.core.model.PlaybackBackend
import com.puretv.twitch.desktop.player.DesktopPlayer
import com.puretv.twitch.desktop.player.DesktopPlayerProvider
import com.puretv.twitch.core.emotes.EmoteCache
import com.puretv.twitch.core.emotes.PersistentEmoteCache
import com.puretv.twitch.core.storage.FileTextStore
import java.io.File
import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath
import com.puretv.twitch.desktop.player.LocalStreamProxy
import com.puretv.twitch.desktop.player.MpvPlayer
import com.puretv.twitch.desktop.player.VlcEngine
import com.puretv.twitch.desktop.player.VlcPlayer
import com.puretv.twitch.desktop.update.UpdateManager
import com.puretv.twitch.core.follows.FollowedChannelsService
import com.puretv.twitch.core.follows.FollowedChannelsSource
import com.puretv.twitch.core.follows.FollowedRef
import com.puretv.twitch.desktop.ui.BrowseViewModel
import com.puretv.twitch.desktop.ui.CategoryViewModel
import com.puretv.twitch.desktop.ui.ChannelStatsViewModel
import com.puretv.twitch.desktop.ui.ChannelViewModel
import com.puretv.twitch.desktop.ui.FollowedRailViewModel
import com.puretv.twitch.desktop.ui.HomeViewModel
import com.puretv.twitch.desktop.ui.LoginViewModel
import com.puretv.twitch.desktop.ui.MultiViewViewModel
import com.puretv.twitch.desktop.ui.SearchViewModel
import com.puretv.twitch.desktop.ui.SettingsViewModel
import com.puretv.twitch.desktop.ui.emotes.EmoteFrameCache
import com.puretv.twitch.desktop.ui.StreamViewModel
import com.puretv.twitch.desktop.ui.VodListViewModel
import com.puretv.twitch.desktop.ui.VodLaunch
import com.puretv.twitch.desktop.ui.VodChatViewModel
import com.puretv.twitch.desktop.ui.VodPlayerViewModel
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

/**
 * SECTION 11/12.4: Windows-specific Koin bindings layered on top of
 * `coreModule` (shared API client, repositories, ad-block engine, chat
 * client - Sections 3 to 5). Mirrors `androidModule`/`tvModule` in shape;
 * differs in *what* backs persistence (file-based, not Room/DataStore) and
 * playback (VLCJ + local proxy, not ExoPlayer).
 *
 * NOTE - ViewModels are bound with `factory { }`, not Koin's `viewModel { }`
 * DSL (that's an AndroidX-lifecycle integration this plain-JVM module doesn't
 * pull in). Screens obtain them via `rememberDesktopViewModel { koin.get() }`
 * / `rememberDesktopViewModel(login) { koin.get { parametersOf(login) } }`.
 */
val desktopModule = module {
    // --- Networking engine (coreModule provides `single { buildKtorClient(get()) }`,
    // which resolves an `HttpClientEngine` - OkHttp here, matching Android/TV). ---
    single<HttpClientEngine> { OkHttp.create() }

    // --- Persistence -------------------------------------------------------
    // Takes TokenHolder (from coreModule) so saved tokens flow back into the
    // shared HttpClient auth path on both fresh login and cold-start restore.
    single { DesktopSettingsStore(get()) }
    // Local "Following" list (the in-app library) - see FollowStore.
    single { FollowStore() }
    // Go-live alerts. The monitor is the app's one asker of "which follows are live"
    // (see LiveFollowsMonitor): the rail reads through it, the alert timer in main.kt
    // polls it, and whatever just went live becomes a tray notification.
    single { TrayState() }
    single { NavigationRequests() }
    single { DesktopLiveNotifier() }
    single { LiveNotifyFileStore() }
    single {
        val store = get<DesktopSettingsStore>()
        val api = get<TwitchApiClient>()
        val notifier = get<DesktopLiveNotifier>()
        LiveFollowsMonitor(
            fetchLive = { userId -> api.getFollowedStreams(userId) },
            store = get<LiveNotifyFileStore>(),
            settings = { store.settings.value },
            userId = { if (store.isLoggedIn) store.sessionUserId else null },
            clock = System::currentTimeMillis,
            onGoLive = { streams -> notifier.show(streams) },
        )
    }
    // Followed-rail data: real Twitch follows (paginated) plus local pins, with live
    // status from the shared monitor.
    single<FollowedChannelsSource> {
        val monitor = get<LiveFollowsMonitor>()
        FollowedChannelsService(get(), followedLive = { userId -> monitor.followedLive(userId) })
    }
    // Per-VOD playback positions ("continue watching") - see WatchProgressStore.
    single { WatchProgressStore() }
    // Locally-persisted viewer-count history for the channel stats panel - see ViewerHistoryStore.
    single { ViewerHistoryStore() }
    // In-app auto-updater (GitHub Releases) - see UpdateManager.
    single { UpdateManager() }
    // One Coil disk cache for the whole app: the image loader fills it with
    // avatars, thumbnails and emotes, and the animated-emote decoder reads from
    // it, so an emote already on disk is never downloaded again. Bounded so it
    // can never grow without limit.
    single<DiskCache> {
        DiskCache.Builder()
            .directory(File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PureTwitch/image_cache").toOkioPath())
            .maxSizeBytes(256L * 1024 * 1024)
            .build()
    }
    single { EmoteFrameCache(get(), get()) }
    // Emote lists survive restarts so chat shows emotes on its first lines.
    single<EmoteCache> {
        val localData = System.getenv("LOCALAPPDATA") ?: System.getenv("APPDATA") ?: System.getProperty("user.home")
        PersistentEmoteCache(FileTextStore(File(localData, "PureTwitch/cache/emotes")))
    }

    // --- Playback ------------------------------------------------------------
    // Built on first use (the background warm-up just after the first window, or
    // the first stream open), never on the way to the first window: creating it
    // loads libVLC and its plugins, or the 120 MB libmpv.
    // The one libVLC instance: the single-stream player and every multi-stream tile make
    // their media players from it. VLC takes the audio normalizer only as instance
    // arguments, read when libVLC starts.
    single {
        val store = get<DesktopSettingsStore>()
        VlcEngine { if (store.settings.value.audioNormalizer) AudioNormalizerProfile.vlcArgs() else emptyList() }
    }
    single {
        val store = get<DesktopSettingsStore>()
        val engine = get<VlcEngine>()
        DesktopPlayerProvider {
            if (store.settings.value.playbackBackend == PlaybackBackend.MPV) MpvPlayer(store) else VlcPlayer(engine)
        }
    }
    single<DesktopPlayer> { get<DesktopPlayerProvider>().player }
    // LocalStreamProxy takes HttpClient (variant fetches) + BackupStreamResolver
    // (the player-type swap that actually removes ads) on top of the shared
    // PlayableStreamCache and AdBlockEngine. See the /variant route.
    single {
        val settingsStore = get<DesktopSettingsStore>()
        LocalStreamProxy(get(), get(), get(), get(), lowLatency = { settingsStore.settings.value.lowLatencyMode })
    }

    // --- Auth ------------------------------------------------------------------
    single { DesktopOAuthManager() }

    // --- ViewModels (plain factories - see note above) -------------------------
    factory { HomeViewModel(get(), get(), get(), get()) }
    factory {
        val store = get<DesktopSettingsStore>()
        val follows = get<FollowStore>()
        FollowedRailViewModel(
            source = get(),
            loggedInUserId = { if (store.isLoggedIn) store.sessionUserId else null },
            localPins = { follows.followed.value.map { FollowedRef(it.id, it.login, it.displayName) } },
        )
    }
    factory { BrowseViewModel(get()) }
    // Category drill-down takes (gameId, gameName) from the tapped Browse card.
    factory { (gameId: String, gameName: String) -> CategoryViewModel(gameId, gameName, get()) }
    factory { SearchViewModel(get()) }
    factory { (channelLogin: String) ->
        StreamViewModel(channelLogin, get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
    }
    factory { (channelLogin: String) ->
        MultiViewViewModel(channelLogin, get(), get(), get(), get(), get(), get())
    }
    factory { (channelLogin: String) -> ChannelViewModel(channelLogin, get(), get(), get()) }
    factory { (channelLogin: String) -> ChannelStatsViewModel(channelLogin, get(), get()) }
    factory {
        val players = get<DesktopPlayerProvider>()
        // Live on mpv; never builds a player just to tell it about the setting.
        SettingsViewModel(get(), onAudioNormalizerChange = { on -> players.ifCreated { it.setAudioNormalizer(on) } })
    }
    // LoginViewModel collaborators: settingsStore, oauthManager, httpClient,
    // tokenHolder (for "set token BEFORE first authed call"), apiClient (for
    // resolving the broadcaster's own userId/login via GET /users).
    factory { LoginViewModel(get(), get(), get(), get(), get()) }
    factory { (userId: String) -> VodListViewModel(userId, get()) }
    factory { (launch: VodLaunch) -> VodPlayerViewModel(launch, get(), get(), get(), get()) }
    factory { (vodId: String, channelLogin: String) -> VodChatViewModel(vodId, channelLogin, get(), get(), get(), get()) }
}

/** Convenience for screens: `koin.get<StreamViewModel> { parametersOf(channelLogin) }`. */
internal inline fun <reified T : Any> org.koin.core.Koin.getWithParam(param: Any): T =
    get(parameters = { parametersOf(param) })
