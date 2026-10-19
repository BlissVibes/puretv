package com.puretv.twitch.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
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
import com.puretv.twitch.desktop.ui.App
import com.puretv.twitch.desktop.ui.NavigationRequests
import io.ktor.client.HttpClient
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
        focusExistingWindow = { SingleInstance.focusWindowTitled(WINDOW_TITLE) },
    )
    if (!guard.acquire()) exitProcess(0)
    instanceGuard = guard
    val koinApp = koinApplication {
        modules(coreModule, desktopModule)
    }
    startKoin(koinApp)

    val navRequests = koinApp.koin.get<NavigationRequests>()
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

    // Go-live alerts: one /streams/followed call every LIVE_POLL_INTERVAL_MS, counted
    // from the last time anything asked (the followed rail refreshing while the window
    // is focused counts, so they never double up). Runs only while alerts are on,
    // someone is signed in and Windows has a tray to show them in; collectLatest
    // cancels the loop the moment any of those stops being true.
    val monitor = koinApp.koin.get<LiveFollowsMonitor>()
    backgroundScope.launch {
        combine(settingsStore.settings.map { it.liveNotifications }, settingsStore.loggedInState) { on, signedIn ->
            on && signedIn && isTraySupported
        }
            .distinctUntilChanged()
            .collectLatest { active -> if (active) monitor.pollEvery(LIVE_POLL_INTERVAL_MS) }
    }
    // "Start with Windows" points at the launcher exe; rewrite it on each start so it
    // follows the app if it was reinstalled somewhere else.
    if (settingsStore.settings.value.startWithWindows) {
        backgroundScope.launch { WindowsStartup.setEnabled(true) }
    }

    val playerProvider = koinApp.koin.get<DesktopPlayerProvider>()
    val localStreamProxy = koinApp.koin.get<LocalStreamProxy>()
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
        val signedIn by settingsStore.loggedInState.collectAsState()
        var windowVisible by remember { mutableStateOf(!startHidden) }
        var frontRequests by remember { mutableIntStateOf(0) }

        // The window's close (title bar X, Alt+F4). Hides to the tray only when the
        // viewer chose that and there is a tray; otherwise, and always for a close that
        // arrives while already hidden (Windows signing out, an installer), it quits.
        // The updater and the tray's Quit never come through here: they call
        // exitApplication directly, so an update always really exits.
        val closeWindow: () -> Unit = {
            if (windowVisible && settings.closeToTray && isTraySupported) {
                windowVisible = false
            } else {
                exitApplication()
            }
        }

        LaunchedEffect(Unit) {
            navRequests.showWindow.collect {
                windowVisible = true
                windowState.isMinimized = false
                frontRequests++
            }
        }

        // Alerts are delivered through the tray icon, so it is there whenever they can
        // arrive, and whenever the window is hidden (the only way back to it).
        if (isTraySupported && ((settings.liveNotifications && signedIn) || settings.closeToTray || !windowVisible)) {
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
                    CheckboxItem(
                        "Go-live alerts",
                        checked = settings.liveNotifications,
                        onCheckedChange = { on -> settingsStore.updateSettings { it.copy(liveNotifications = on) } },
                    )
                    Separator()
                    Item("Quit PureTV", onClick = ::exitApplication)
                },
            )
        }

        Window(
            onCloseRequest = closeWindow,
            visible = windowVisible,
            state = windowState,
            title = WINDOW_TITLE,
            icon = appIcon,
            undecorated = true,
        ) {
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
                onQuit = ::exitApplication,
                awtWindow = window,
                windowVisible = windowVisible,
            )
        }
    }

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
    runCatching { runBlocking { withTimeoutOrNull(3_000) { localStreamProxy.stop() } } }
    runCatching { playerProvider.releaseIfCreated() }
    exitProcess(0)
}

/** The main window's title; a second launch finds the running copy by it. */
private const val WINDOW_TITLE = "PureTV for Twitch"

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
