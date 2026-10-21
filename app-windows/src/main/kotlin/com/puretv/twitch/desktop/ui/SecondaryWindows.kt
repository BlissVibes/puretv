package com.puretv.twitch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.puretv.twitch.desktop.platform.WindowsNative
import com.puretv.twitch.desktop.player.ChannelClaims
import com.puretv.twitch.desktop.player.VlcEngine
import com.puretv.twitch.desktop.player.WindowPlayback
import java.awt.Dimension
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.Koin

/**
 * The PureTV windows opened with "New window" or "Open in new window": one OS window per
 * secondary entry of the [WindowRegistry], each a whole [App] with its own screens and its
 * own player. Called from the application, outside the main window, so they stay up when
 * the main window is on another screen. [visible] follows the main window (hiding it to
 * the tray hides them all), [frontRequests] counts requests to bring everything forward.
 *
 * Shared with the main window: sign-in, settings, follows, caches, the update banner and the
 * theme, because they come from the same Koin graph.
 */
@Composable
fun SecondaryWindows(koin: Koin, visible: Boolean, frontRequests: Int, icon: Painter?, onQuit: () -> Unit) {
    val registry = remember { koin.get<WindowRegistry>() }
    val windows by registry.windows.collectAsState()
    val boundsStore = remember { WindowBoundsStores.shared }
    windows.filter { it.kind == WindowKind.SECONDARY }.forEach { entry ->
        key(entry.id) {
            SecondaryWindow(koin, registry, boundsStore, entry, visible, frontRequests, icon, onQuit)
        }
    }
}

/** Where each window slot was left, kept next to the other desktop files. */
internal object WindowBoundsStores {
    val shared: PopoutBoundsStore by lazy {
        PopoutBoundsStore(File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PureTwitch/app-windows.txt"))
    }
}

@Composable
private fun SecondaryWindow(
    koin: Koin,
    registry: WindowRegistry,
    boundsStore: PopoutBoundsStore,
    entry: WindowEntry,
    visible: Boolean,
    frontRequests: Int,
    icon: Painter?,
    onQuit: () -> Unit,
) {
    val slotKey = "slot${entry.slot}"
    // Where it opens is decided once: where this slot was left, otherwise another monitor.
    val initial = remember(entry.id) {
        val works = PopoutLayout.screenWorkAreas()
        val open = AppWindows.all().filter { it.isShowing }.map { MiniRect(it.x, it.y, it.width, it.height) }
        val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        val inUse = (active?.takeIf { w -> AppWindows.all().any { it === w } } ?: AppWindows.all().firstOrNull())
            ?.let { MiniRect(it.x, it.y, it.width, it.height) }
        val fresh = NewWindowLayout.place(works, inUse?.let { PopoutLayout.monitorOf(it, works) }, open)
        if (fresh == null) null else NewWindowLayout.resolve(boundsStore.recall(slotKey), fresh, works)
    }
    val windowState = rememberWindowState(
        position = initial?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
        size = initial?.let { DpSize(it.width.dp, it.height.dp) }
            ?: DpSize(NewWindowLayout.DEFAULT_WIDTH.dp, NewWindowLayout.DEFAULT_HEIGHT.dp),
    )

    // This window's own player, made off the UI thread: the first one loads libVLC, which is slow.
    val secondary = remember(entry.id) { WindowPlayback.secondary(koin.get<VlcEngine>(), koin.get<ChannelClaims>()) }
    var ready by remember(entry.id) { mutableStateOf(false) }
    LaunchedEffect(secondary) {
        ready = withContext(Dispatchers.IO) { runCatching { secondary.prepare() }.getOrDefault(false) }
    }
    DisposableEffect(secondary) { onDispose { secondary.release() } }
    DisposableEffect(entry.id) {
        onDispose { koin.get<com.puretv.twitch.desktop.notify.DesktopLiveNotifier>().watchingIn(entry.id, null) }
    }

    Window(
        onCloseRequest = { registry.close(entry.id) },
        visible = visible,
        state = windowState,
        title = entry.title.ifBlank { windowTitle(entry.slot, null) },
        icon = icon,
        undecorated = true,
    ) {
        val awtWindow = window
        LaunchedEffect(visible) {
            if (!visible) return@LaunchedEffect
            if (!WindowsNative.enableBorderlessSnap(awtWindow)) {
                SwingUtilities.invokeLater { WindowsNative.enableBorderlessSnap(awtWindow) }
            }
        }
        LaunchedEffect(frontRequests) {
            if (frontRequests == 0) return@LaunchedEffect
            if (awtWindow.extendedState and Frame.ICONIFIED != 0) awtWindow.extendedState = awtWindow.extendedState and Frame.ICONIFIED.inv()
            awtWindow.toFront()
        }
        DisposableEffect(awtWindow) {
            AppWindows.add(awtWindow)
            awtWindow.minimumSize = Dimension(NewWindowLayout.MIN_WIDTH, NewWindowLayout.MIN_HEIGHT)
            // Where the window is left is remembered for its slot (maximized is not a place).
            val flushSoon = javax.swing.Timer(FLUSH_DELAY_MS) { boundsStore.flush() }.apply { isRepeats = false }
            val track = object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent) = save()
                override fun componentResized(e: ComponentEvent) = save()
                private fun save() {
                    if (awtWindow.extendedState and Frame.MAXIMIZED_BOTH != 0) return
                    // A fullscreen player covers the whole monitor; that is not a place to come back to.
                    if (awtWindow.graphicsConfiguration?.bounds?.let { it.width == awtWindow.width && it.height == awtWindow.height } == true) return
                    boundsStore.remember(slotKey, MiniRect(awtWindow.x, awtWindow.y, awtWindow.width, awtWindow.height))
                    flushSoon.restart()
                }
            }
            awtWindow.addComponentListener(track)
            onDispose {
                awtWindow.removeComponentListener(track)
                flushSoon.stop()
                AppWindows.remove(awtWindow)
                boundsStore.flush()
            }
        }
        if (ready) {
            App(
                koin = koin,
                windowState = windowState,
                onClose = { registry.close(entry.id) },
                onQuit = onQuit,
                awtWindow = awtWindow,
                onWatching = { koin.get<com.puretv.twitch.desktop.notify.DesktopLiveNotifier>().watchingIn(entry.id, it) },
                windowVisible = visible,
                frontRequests = frontRequests,
                windowId = entry.id,
                slot = entry.slot,
                start = entry.start,
                playback = secondary.playback,
            )
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF0E0E10)))
        }
    }
}

private const val FLUSH_DELAY_MS = 600
