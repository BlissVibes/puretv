package com.puretv.twitch.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.WindowPosition
import com.puretv.twitch.desktop.ui.MinimizeAction
import com.puretv.twitch.desktop.ui.WindowBoundsGate
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import com.puretv.twitch.core.di.coreModule
import com.puretv.twitch.core.notify.LiveFollowsMonitor
import com.puretv.twitch.desktop.data.DesktopSettingsStore
import com.puretv.twitch.desktop.data.FollowStore
import com.puretv.twitch.desktop.data.LiveNotifyFileStore
import com.puretv.twitch.desktop.data.ViewerHistoryStore
import com.puretv.twitch.desktop.data.WatchProgressStore
import com.puretv.twitch.desktop.di.desktopModule
import com.puretv.twitch.core.perf.TimingLog
import com.puretv.twitch.desktop.notify.DesktopLiveNotifier
import com.puretv.twitch.desktop.platform.Diagnostics
import com.puretv.twitch.desktop.platform.SingleInstance
import com.puretv.twitch.desktop.platform.TimingFileSink
import com.puretv.twitch.desktop.platform.WindowsNative
import com.puretv.twitch.desktop.platform.WindowsStartup
import com.puretv.twitch.desktop.player.LocalStreamProxy
import com.puretv.twitch.desktop.player.DesktopPlayerProvider
import com.puretv.twitch.desktop.player.VlcEngine
import androidx.compose.runtime.DisposableEffect
import com.puretv.twitch.desktop.ui.App
import com.puretv.twitch.desktop.ui.AppWindows
import com.puretv.twitch.desktop.ui.CloseAction
import com.puretv.twitch.desktop.ui.PRIMARY_WINDOW_TITLE
import com.puretv.twitch.desktop.ui.SecondaryWindows
import com.puretv.twitch.desktop.ui.WindowDestination
import com.puretv.twitch.desktop.ui.WindowRegistry
import com.puretv.twitch.desktop.ui.openInNewWindow
import com.puretv.twitch.desktop.ui.MultiSession
import com.puretv.twitch.desktop.ui.NavigationRequests
import com.puretv.twitch.desktop.ui.PoppedStreamWindows
import io.ktor.client.HttpClient
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess
import org.koin.core.context.GlobalContext.startKoin
import org.koin.dsl.koinApplication

fun main(args: Array<String>) {
    TimingFileSink.install()
    Diagnostics.installCrashLog()
    // One running copy only: a second launch brings the open window forward (or out
    // of the tray) and exits.
    val guard = SingleInstance(
        lockFile = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PureTwitch/instance.lock"),
        focusExistingWindow = { SingleInstance.focusWindowTitled(PRIMARY_WINDOW_TITLE) },
    )
    if (!guard.acquire()) exitProcess(0)
    instanceGuard = guard
    val koinApp = koinApplication {
        modules(coreModule, desktopModule)
    }
    startKoin(koinApp)

    val navRequests = koinApp.koin.get<NavigationRequests>()
    // Also installs the registry that openInNewWindow() uses.
    val registry = koinApp.koin.get<WindowRegistry>()
    guard.listenForActivation { navRequests.requestShow() }

    val settingsStore = koinApp.koin.get<DesktopSettingsStore>()
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Keep the session fresh for the life of the process: refresh a near-expired
    // access token at startup (audit F2), then check again every minute, so a window
    // left open past the ~4h token lifetime (or a laptop waking from sleep) gets a
    // new token instead of quietly failing every Helix call and chat send. Each
    // check is a no-op until the token is within its refresh leeway. Background
    // scope, so it never blocks UI startup.
    backgroundScope.launch {
        val http = koinApp.koin.get<HttpClient>()
        while (true) {
            runCatching { settingsStore.refreshIfNeeded(http) }
            delay(SESSION_REFRESH_CHECK_MS)
        }
    }

    // Go-live alerts: one batched call for the channels the viewer turned the bell on
    // for, every LIVE_POLL_INTERVAL_MS (and at once when that list changes). Runs only
    // while alerts are on, at least one channel is opted in (signed in or not) and
    // Windows has a tray to show them in; collectLatest cancels the loop the moment
    // any of those stops being true, so with no channels there are no network calls.
    val monitor = koinApp.koin.get<LiveFollowsMonitor>()
    backgroundScope.launch {
        settingsStore.settings
            .map { (it.liveNotifications && it.notifyChannels.isNotEmpty() && isTraySupported) to it.notifyChannels }
            .distinctUntilChanged()
            .collectLatest { (active, _) ->
                if (active) {
                    monitor.pollEvery(LIVE_POLL_INTERVAL_MS)
                } else {
                    // Whatever goes live while alerts are off or no channel is opted in is
                    // old news when they resume: the first poll then seeds silently.
                    monitor.alertsStopped()
                }
            }
    }
    // "Start with Windows" points at the launcher exe; rewrite it on each start so it
    // follows the app if it was reinstalled somewhere else.
    if (settingsStore.settings.value.startWithWindows) {
        backgroundScope.launch { WindowsStartup.setEnabled(true) }
    }

    val playerProvider = koinApp.koin.get<DesktopPlayerProvider>()
    val localStreamProxy = koinApp.koin.get<LocalStreamProxy>()
    val vlcEngine = koinApp.koin.get<VlcEngine>()
    Runtime.getRuntime().addShutdownHook(Thread {
        // The stores now persist off the UI thread via a conflated async writer; flush
        // any pending snapshot so a change made right before quitting is still durable.
        runCatching { settingsStore.flush() }
        runCatching { koinApp.koin.get<FollowStore>().flush() }
        runCatching { koinApp.koin.get<WatchProgressStore>().flush() }
        runCatching { koinApp.koin.get<ViewerHistoryStore>().flush() }
        // A lost write here would announce the same streams again on the next start.
        runCatching { koinApp.koin.get<LiveNotifyFileStore>().flush() }
        runCatching { runBlocking { localStreamProxy.stop() } }
        runCatching { playerProvider.releaseIfCreated() }
        runCatching { vlcEngine.releaseIfBuilt() }
    })

    // Create icon once before the Compose application loop so it isn't
    // recreated on every recomposition.
    val appIcon = createAppIcon()
    val trayState = koinApp.koin.get<TrayState>()
    val notifier = koinApp.koin.get<DesktopLiveNotifier>()
    // Started by Windows at sign-in: stay in the tray. Without a tray there would be
    // no way to reach a hidden window, so the window shows after all.
    val startHidden = WindowsStartup.BACKGROUND_ARG in args && isTraySupported

    application {
        val windowState: WindowState = rememberWindowState(width = 1280.dp, height = 720.dp)
        val settings by settingsStore.settings.collectAsState()
        var windowVisible by remember { mutableStateOf(!startHidden) }
        var frontRequests by remember { mutableIntStateOf(0) }

        // The window's close (title bar X, Alt+F4) always quits, hidden or not (a close
        // that arrives while hidden is Windows signing out or an installer). Minimizing
        // is what goes to the tray, see the minimize watcher below.
        // The updater and the tray's Quit never come through here: they call
        // exitApplication directly, so an update always really exits.
        // Every real exit stops answering a second launch first: application{} only
        // returns after Compose has disposed, and a launch arriving in between would
        // have its show request answered (deleted) by a copy that is about to exit.
        val quit: () -> Unit = {
            // Players are stopped and released before the engine goes (see below); the
            // players of the other windows go as their windows leave composition.
            runCatching { koinApp.koin.get<MultiSession>().end() }
            guard.stopListening()
            exitApplication()
        }
        val closeWindow: () -> Unit = {
            when (registry.closeAction(WindowRegistry.PRIMARY_ID)) {
                CloseAction.QUIT -> quit()
                else -> Unit
            }
        }
        // Where the window last sat in a normal state, so a minimize that makes Windows
        // report a parked position (-32000) cannot become the place it comes back to.
        val lastNormalPosition = remember { arrayOfNulls<java.awt.Point>(1) }
        // Minimizing the main window sends PureTV to the tray: the window is put back to
        // normal at once (so it comes back un-iconified) and then everything hides, the
        // same way as any hide: each window stops what it plays and leaves mini/fullscreen.
        // Our own restore flips isMinimized back to false, which this ignores, so there is
        // no loop; the show path also clears it. Without a tray it stays a taskbar minimize.
        LaunchedEffect(Unit) {
            snapshotFlow { windowState.isMinimized }.collect { minimized ->
                if (!minimized) return@collect
                if (registry.minimizeAction(WindowRegistry.PRIMARY_ID, windowVisible, isTraySupported) != MinimizeAction.HIDE_ALL) return@collect
                windowState.isMinimized = false
                lastNormalPosition[0]?.let { p ->
                    if (windowState.position.isSpecified && windowState.position.x.value <= -30_000f) {
                        windowState.position = WindowPosition(p.x.dp, p.y.dp)
                    }
                }
                windowVisible = false
            }
        }
        val windows by registry.windows.collectAsState()
        val mainTitle = windows.firstOrNull()?.title?.ifBlank { null } ?: PRIMARY_WINDOW_TITLE

        LaunchedEffect(Unit) {
            navRequests.showWindow.collect {
                windowVisible = true
                windowState.isMinimized = false
                frontRequests++
            }
        }
        // A click on a go-live notification opens that stream in the window used last.
        LaunchedEffect(Unit) {
            navRequests.openStream.collect { login ->
                // The window may not have started listening yet (a start in the tray).
                repeat(50) {
                    if (registry.navigate(WindowDestination.Stream(login))) return@collect
                    delay(100)
                }
                // Still nobody ready: open the stream in a window of its own rather than lose the click.
                openInNewWindow(WindowDestination.Stream(login))
            }
        }
        // A popped-out tile asked for the multi-stream grid (chat, add a channel, dock back).
        LaunchedEffect(Unit) {
            koinApp.koin.get<MultiSession>().openGrid.collect {
                registry.openMultiGrid()
                navRequests.requestShow()
            }
        }

        // The tray icon is there for as long as PureTV runs: minimizing hides the window
        // to it, and it is also where go-live alerts arrive.
        if (isTraySupported) {
            // The icon is added to the system tray as Tray enters composition; this runs
            // right after, so a go-live toast that had nowhere to go yet appears now.
            LaunchedEffect(Unit) { notifier.flushPending() }
            Tray(
                icon = appIcon,
                state = trayState,
                tooltip = WINDOW_TITLE,
                // A double-click on the icon, or a click on a go-live notification.
                onAction = {
                    val login = notifier.consumeRecentTarget()
                    if (login != null) navRequests.requestStream(login) else navRequests.requestShow()
                },
                menu = {
                    Item("Open PureTV", onClick = { navRequests.requestShow() })
                    Item("New window", onClick = { navRequests.requestShow(); openInNewWindow(WindowDestination.Home) })
                    CheckboxItem(
                        "Go-live alerts",
                        checked = settings.liveNotifications,
                        onCheckedChange = { on -> settingsStore.updateSettings { it.copy(liveNotifications = on) } },
                    )
                    Separator()
                    Item("Quit PureTV", onClick = quit)
                },
            )
        }

        Window(
            onCloseRequest = closeWindow,
            visible = windowVisible,
            state = windowState,
            title = mainTitle,
            icon = appIcon,
            undecorated = true,
        ) {
            DisposableEffect(window) {
                AppWindows.add(window)
                onDispose { AppWindows.remove(window) }
            }
            DisposableEffect(window) {
                val track = object : ComponentAdapter() {
                    override fun componentMoved(e: ComponentEvent) {
                        if (WindowBoundsGate.shouldSave(window.extendedState, window.x, window.y)) {
                            lastNormalPosition[0] = java.awt.Point(window.x, window.y)
                        }
                    }
                }
                window.addComponentListener(track)
                onDispose { window.removeComponentListener(track) }
            }
            // Re-enable Windows-native window management (Aero Snap, Snap Layouts,
            // Win+Arrow, edge-resize) on our undecorated frame. Runs once the
            // window is first shown (a start in the tray waits until then, since a
            // never-shown window may have no native handle yet); one EDT-deferred
            // retry covers the rare case where the HWND isn't bound yet.
            var shownOnce by remember { mutableStateOf(false) }
            LaunchedEffect(windowVisible) {
                if (!windowVisible || shownOnce) return@LaunchedEffect
                shownOnce = true
                TimingLog.log("app.first_window", TimingFileSink.sinceProcessStartMs())
                // The window is up: now build the player in the background, so the
                // first stream open finds it ready without having delayed startup.
                launch(Dispatchers.IO) { runCatching { playerProvider.warmUp() } }
                if (!WindowsNative.enableBorderlessSnap(window)) {
                    SwingUtilities.invokeLater { WindowsNative.enableBorderlessSnap(window) }
                }
            }
            // Asked to come forward (a second launch, the tray): restore and raise.
            LaunchedEffect(frontRequests) {
                if (frontRequests == 0) return@LaunchedEffect
                if (window.extendedState and Frame.ICONIFIED != 0) window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
                window.toFront()
                window.requestFocus()
            }
            // `window` is FrameWindowScope.window (ComposeWindow extends java.awt.Frame).
            // Pass it to App so the custom title bar can move the window without
            // relying on LocalWindow (which is library-internal in CMP 1.7).
            App(
                koin = koinApp.koin,
                windowState = windowState,
                onClose = closeWindow,
                onQuit = quit,
                onWatching = { notifier.watchingLogin = it },
                awtWindow = window,
                windowVisible = windowVisible,
                frontRequests = frontRequests,
            )
        }

        // Windows opened with "New window": whole PureTV windows with their own screens and player.
        SecondaryWindows(
            koin = koinApp.koin,
            visible = windowVisible,
            frontRequests = frontRequests,
            icon = appIcon,
            onQuit = quit,
        )

        // Streams popped out of multi-stream live in windows of their own, beside the main one.
        PoppedStreamWindows(
            koin = koinApp.koin,
            visible = windowVisible,
            frontRequests = frontRequests,
            icon = appIcon,
        )
    }

    // No more answering a relaunch (a no-op if quit() already stopped it): the slow
    // teardown below is exactly when a quick relaunch would be lost.
    guard.stopListening()

    // The Compose UI loop has ended (window closed, the tray's Quit, or the updater
    // called exitApplication). Returning from main() is NOT enough to end the process:
    // the Netty stream proxy runs NON-DAEMON threads that keep the JVM alive
    // indefinitely, and the shutdown hook that stops it only runs once the JVM is
    // already exiting, a standoff that left the process (and its file locks)
    // lingering long after the window closed. That lingering process is what
    // corrupted in-place updates (files replaced while still locked). Tear the
    // proxy down explicitly (bounded so a wedged stop can't hang the quit), release
    // the player, then hard-exit. exitProcess still runs the shutdown hook above,
    // which flushes the persistence stores.
    runCatching { koinApp.koin.get<MultiSession>().end() }
    runCatching { runBlocking { withTimeoutOrNull(3_000) { localStreamProxy.stop() } } }
    runCatching { playerProvider.releaseIfCreated() }
    runCatching { vlcEngine.releaseIfBuilt() }
    exitProcess(0)
}

private const val WINDOW_TITLE = PRIMARY_WINDOW_TITLE

/**
 * Held for the life of the process. The lock lives in a file channel, and an
 * unreachable channel can be closed by the garbage collector, which would quietly
 * release the lock while PureTV is still running.
 */
@Suppress("unused")
private var instanceGuard: SingleInstance? = null

/** How often the background session check runs (see main). Cheap: one small file decrypt. */
private const val SESSION_REFRESH_CHECK_MS = 60_000L

/** Go-live alert cadence: one small Helix call, so an alert arrives within about 90 s. */
private const val LIVE_POLL_INTERVAL_MS = 90_000L
