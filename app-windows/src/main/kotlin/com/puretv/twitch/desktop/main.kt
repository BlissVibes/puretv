package com.puretv.twitch.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.puretv.twitch.core.di.coreModule
import com.puretv.twitch.desktop.data.DesktopSettingsStore
import com.puretv.twitch.desktop.data.FollowStore
import com.puretv.twitch.desktop.data.ViewerHistoryStore
import com.puretv.twitch.desktop.data.WatchProgressStore
import com.puretv.twitch.desktop.di.desktopModule
import com.puretv.twitch.core.perf.TimingLog
import com.puretv.twitch.desktop.platform.TimingFileSink
import com.puretv.twitch.desktop.platform.WindowsNative
import com.puretv.twitch.desktop.player.LocalStreamProxy
import com.puretv.twitch.desktop.player.DesktopPlayer
import com.puretv.twitch.desktop.ui.App
import io.ktor.client.HttpClient
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess
import org.koin.core.context.GlobalContext.startKoin
import org.koin.dsl.koinApplication

fun main() {
    TimingFileSink.install()
    val koinApp = koinApplication {
        modules(coreModule, desktopModule)
    }
    startKoin(koinApp)

    val settingsStore = koinApp.koin.get<DesktopSettingsStore>()
    // Keep the session fresh for the life of the process: refresh a near-expired
    // access token at startup (audit F2), then check again every minute, so a window
    // left open past the ~4h token lifetime (or a laptop waking from sleep) gets a
    // new token instead of quietly failing every Helix call and chat send. Each
    // check is a no-op until the token is within its refresh leeway. Background
    // scope, so it never blocks UI startup.
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        val http = koinApp.koin.get<HttpClient>()
        while (true) {
            runCatching { settingsStore.refreshIfNeeded(http) }
            delay(SESSION_REFRESH_CHECK_MS)
        }
    }

    val vlcPlayer = koinApp.koin.get<DesktopPlayer>()
    val localStreamProxy = koinApp.koin.get<LocalStreamProxy>()
    Runtime.getRuntime().addShutdownHook(Thread {
        // The stores now persist off the UI thread via a conflated async writer; flush
        // any pending snapshot so a change made right before quitting is still durable.
        runCatching { settingsStore.flush() }
        runCatching { koinApp.koin.get<FollowStore>().flush() }
        runCatching { koinApp.koin.get<WatchProgressStore>().flush() }
        runCatching { koinApp.koin.get<ViewerHistoryStore>().flush() }
        runCatching { runBlocking { localStreamProxy.stop() } }
        runCatching { vlcPlayer.release() }
    })

    // Create icon once before the Compose application loop so it isn't
    // recreated on every recomposition.
    val appIcon = createAppIcon()

    application {
        val windowState: WindowState = rememberWindowState(width = 1280.dp, height = 720.dp)
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "PureTV for Twitch",
            icon = appIcon,
            undecorated = true,
        ) {
            // Re-enable Windows-native window management (Aero Snap, Snap Layouts,
            // Win+Arrow, edge-resize) on our undecorated frame. Runs once the
            // window is realized; one EDT-deferred retry covers the rare case
            // where the HWND isn't bound yet at first composition.
            LaunchedEffect(Unit) {
                TimingLog.log("app.first_window", TimingFileSink.sinceProcessStartMs())
                if (!WindowsNative.enableBorderlessSnap(window)) {
                    SwingUtilities.invokeLater { WindowsNative.enableBorderlessSnap(window) }
                }
            }
            // `window` is FrameWindowScope.window (ComposeWindow extends java.awt.Frame).
            // Pass it to App so the custom title bar can move the window without
            // relying on LocalWindow (which is library-internal in CMP 1.7).
            App(koin = koinApp.koin, windowState = windowState, onClose = ::exitApplication, awtWindow = window)
        }
    }

    // The Compose UI loop has ended (window closed, or the updater called
    // exitApplication). Returning from main() is NOT enough to end the process:
    // the Netty stream proxy runs NON-DAEMON threads that keep the JVM alive
    // indefinitely, and the shutdown hook that stops it only runs once the JVM is
    // already exiting — a standoff that left the process (and its file locks)
    // lingering long after the window closed. That lingering process is what
    // corrupted in-place updates (files replaced while still locked). Tear the
    // proxy down explicitly (bounded so a wedged stop can't hang the quit), release
    // the player, then hard-exit. exitProcess still runs the shutdown hook above,
    // which flushes the persistence stores.
    runCatching { runBlocking { withTimeoutOrNull(3_000) { localStreamProxy.stop() } } }
    runCatching { vlcPlayer.release() }
    exitProcess(0)
}

/** How often the background session check runs (see main). Cheap: one small file decrypt. */
private const val SESSION_REFRESH_CHECK_MS = 60_000L
