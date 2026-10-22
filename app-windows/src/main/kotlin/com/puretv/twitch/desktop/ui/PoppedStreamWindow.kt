package com.puretv.twitch.desktop.ui

import com.puretv.twitch.desktop.platform.ShortcutKey
import com.puretv.twitch.desktop.platform.WindowChrome
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.puretv.twitch.core.adblock.AdBlockStatus
import com.puretv.twitch.desktop.data.DesktopSettingsStore
import com.puretv.twitch.desktop.platform.WindowsNative
import com.puretv.twitch.desktop.player.VlcPlayerView
import com.puretv.twitch.desktop.ui.components.AdBlockPill
import com.puretv.twitch.desktop.ui.components.Avatar
import com.puretv.twitch.desktop.ui.components.ExpressiveIconButton
import com.puretv.twitch.desktop.ui.components.ExpressiveIcons
import com.puretv.twitch.desktop.ui.components.ExpressiveSlider
import com.puretv.twitch.desktop.ui.components.VodTransportRow
import com.puretv.twitch.desktop.ui.theme.PureTvDesktopTheme
import com.puretv.twitch.desktop.ui.theme.PureTvTheme
import com.puretv.twitch.desktop.ui.theme.ShapeIntensity
import com.puretv.twitch.desktop.ui.theme.ThemeVariant
import kotlinx.coroutines.delay
import org.koin.core.Koin
import java.awt.Dimension
import java.awt.Frame
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

private val VIDEO_FRAME = 2.dp

/** How long after the last move or resize a popped-out window's place is written to disk. */
private const val FLUSH_DELAY_MS = 800

/**
 * The windows of the tiles that were popped out of the multi-stream grid, one OS window per
 * tile. Called from the application, outside the main window, so they stay up when the main
 * window goes to another screen. [visible] follows the main window; hiding it to the tray
 * ends the session (App), so they close with it. [frontRequests] counts requests to bring
 * everything forward (a second launch, the tray's Open PureTV).
 *
 * Each window plays the tile's own [com.puretv.twitch.desktop.player.VlcPlayer], which is
 * not recreated: only the video surface moves, and the stream rebuffers for a second or two.
 */
@Composable
fun PoppedStreamWindows(koin: Koin, visible: Boolean, frontRequests: Int, icon: Painter?) {
    val session = remember { koin.get<MultiSession>() }
    val host by session.current.collectAsState()
    val settings by remember { koin.get<DesktopSettingsStore>() }.settings.collectAsState()

    // The timer ends the whole session, popped-out windows included, even when the main
    // window is on a screen that does not watch it.
    LaunchedEffect(session.sleep.ui.running) {
        while (session.sleep.ui.running) {
            delay(1_000)
            if (session.sleep.tick().expired) session.end()
        }
    }

    val viewModel = host ?: return
    val state by viewModel.state.collectAsState()
    PureTvDesktopTheme(ThemeVariant.fromKey(settings.theme), ShapeIntensity.fromKey(settings.shapeIntensity)) {
        state.layout.popped.forEach { tile ->
            key(tile.id) {
                val info = state.info[tile.id]
                if (info != null) {
                    PoppedStreamWindow(
                        session = session,
                        viewModel = viewModel,
                        tile = tile,
                        info = info,
                        layout = state.layout,
                        visible = visible,
                        frontRequests = frontRequests,
                        icon = icon,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PoppedStreamWindow(
    session: MultiSession,
    viewModel: MultiViewViewModel,
    tile: MultiTile,
    info: TileInfo,
    layout: MultiViewState,
    visible: Boolean,
    frontRequests: Int,
    icon: Painter?,
) {
    val c = PureTvTheme.colors
    // Where it opens is decided once, when the tile is popped out.
    val initial = remember(tile.id) { session.placementFor(tile.id, tile.placeKey, layout.popped.size - 1) }
    val windowState = rememberWindowState(
        position = initial?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
        size = initial?.let { DpSize(it.width.dp, it.height.dp) }
            ?: DpSize(PopoutLayout.DEFAULT_WIDTH.dp, PopoutLayout.heightFor(PopoutLayout.DEFAULT_WIDTH).dp),
    )
    var fullscreen by remember { mutableStateOf(false) }
    val gate = remember { PopoutSaveGate() }
    val latestLayout = rememberUpdatedState(layout)
    val tileId = tile.id

    fun dockBack() {
        // The grid is on the main window's multi-stream screen: bring it up to show the tile.
        session.dock(tileId)
        session.requestGrid()
    }

    Window(
        onCloseRequest = { session.closeTile(tileId) },
        visible = visible,
        state = windowState,
        title = "${tile.vod?.title?.ifBlank { null } ?: info.displayName} - PureTV",
        icon = icon,
        undecorated = WindowChrome.customFrame(),
    ) {
        val awtWindow = window
        remember(awtWindow) { WindowChrome.styleNativeFrame(awtWindow) }
        var restore by remember { mutableStateOf<MiniRect?>(null) }

        // Borderless fullscreen on the monitor the window is on, the way the main window does it:
        // no exclusive mode, which fights the video surface.
        fun setFullscreen(on: Boolean) {
            if (on == gate.fullscreen) return
            if (on && WindowChrome.nativeFullscreen()) {
                gate.fullscreen = true
                windowState.placement = WindowPlacement.Fullscreen
            } else if (!on && WindowChrome.nativeFullscreen()) {
                windowState.placement = WindowPlacement.Floating
                gate.fullscreen = false
            } else if (on) {
                // Before the bounds change: the resize events that follow must not be saved as a place.
                gate.fullscreen = true
                restore = MiniRect(awtWindow.x, awtWindow.y, awtWindow.width, awtWindow.height)
                windowState.placement = WindowPlacement.Floating
                awtWindow.extendedState = Frame.NORMAL
                awtWindow.bounds = awtWindow.graphicsConfiguration.bounds
            } else {
                val back = restore
                restore = null
                awtWindow.extendedState = Frame.NORMAL
                if (back != null) awtWindow.setBounds(back.x, back.y, back.width, back.height)
                gate.fullscreen = false
            }
            fullscreen = on
        }

        // Windows-native edge resize and snapping for the borderless frame, once it is shown.
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
            PoppedWindows.add(awtWindow)
            awtWindow.minimumSize = Dimension(PopoutLayout.MIN_WIDTH, PopoutLayout.heightFor(PopoutLayout.MIN_WIDTH))
            // Where the window is left is remembered for this channel (fullscreen is not a place).
            val flushSoon = javax.swing.Timer(FLUSH_DELAY_MS) { session.boundsStore.flush() }.apply { isRepeats = false }
            val track = object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent) = save()
                override fun componentResized(e: ComponentEvent) = save()
                private fun save() {
                    if (!gate.shouldSave(awtWindow.extendedState and Frame.MAXIMIZED_BOTH != 0)) return
                    session.boundsStore.remember(tile.placeKey,MiniRect(awtWindow.x, awtWindow.y, awtWindow.width, awtWindow.height))
                    // Written a moment after the last change, so a crash does not lose the position.
                    flushSoon.restart()
                }
            }
            awtWindow.addComponentListener(track)
            onDispose {
                awtWindow.removeComponentListener(track)
                flushSoon.stop()
                PoppedWindows.remove(awtWindow)
                session.boundsStore.flush()
            }
        }

        // Keys are read at the AWT level like the other player screens, because the video
        // surface is a native window. They only act while this window is the active one.
        DisposableEffect(awtWindow) {
            val held = HeldKey()
            val dispatcher = KeyEventDispatcher { e ->
                if (KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow !== awtWindow) {
                    // Another window has the keyboard: its key releases never reach here.
                    held.clear()
                    return@KeyEventDispatcher false
                }
                if (e.id == KeyEvent.KEY_PRESSED) session.sleep.onActivity()
                if (e.id == KeyEvent.KEY_RELEASED) held.released(e.keyCode)
                if (e.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
                val isRepeat = held.pressed(e.keyCode)
                // A popped-out window has no text box, so there is no typing to stand down for.
                val action = PopoutShortcuts.actionFor(
                    keyCode = e.keyCode,
                    ctrl = ShortcutKey.isDown(e),
                    alt = e.isAltDown,
                    fullscreen = gate.fullscreen,
                    isRepeat = isRepeat,
                    shift = e.isShiftDown,
                    vod = tile.isVod,
                ) ?: return@KeyEventDispatcher false
                action.seekDeltaMs()?.let { delta ->
                    // Unconsumed while the video cannot seek yet.
                    return@KeyEventDispatcher viewModel.seekBy(tileId, delta)
                }
                when (action) {
                    PopoutShortcutAction.TogglePlayPause -> viewModel.togglePlay(tileId)
                    PopoutShortcutAction.SeekBack, PopoutShortcutAction.SeekForward,
                    PopoutShortcutAction.SeekBackLarge, PopoutShortcutAction.SeekForwardLarge,
                    PopoutShortcutAction.JumpBack, PopoutShortcutAction.JumpForward,
                    -> {}
                    PopoutShortcutAction.VolumeUp -> viewModel.nudgeVolume(tileId, up = true)
                    PopoutShortcutAction.VolumeDown -> viewModel.nudgeVolume(tileId, up = false)
                    PopoutShortcutAction.ToggleMute -> viewModel.toggleMute(tileId)
                    PopoutShortcutAction.ToggleFullscreen -> setFullscreen(!gate.fullscreen)
                    PopoutShortcutAction.ExitFullscreen -> setFullscreen(false)
                    PopoutShortcutAction.CloseTile -> session.closeTile(tileId)
                    PopoutShortcutAction.Dock -> dockBack()
                }
                true
            }
            val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            kfm.addKeyEventDispatcher(dispatcher)
            onDispose { kfm.removeKeyEventDispatcher(dispatcher) }
        }

        val doubleClick = remember { DoubleClickDetector() }
        val active = layout.activeId == tileId
        val audible = layout.isAudible(tileId)
        Column(modifier = Modifier.fillMaxSize().background(c.surfaceLowest)) {
            // The outline is a frame of padding around the video, as in the grid. The video is
            // never scaled or faded: it is a native surface.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(if (active && !fullscreen) c.primary else Color.Black)
                    .padding(if (fullscreen) 0.dp else VIDEO_FRAME)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val player = info.player
                when {
                    player == null && info.message != null -> Text(
                        info.message,
                        color = c.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                    player == null -> Text("Starting...", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    else -> VlcPlayerView(
                        vlcPlayer = player,
                        modifier = Modifier.fillMaxSize(),
                        onUserActivity = { session.sleep.onActivity() },
                        onClick = {
                            viewModel.setActive(tileId)
                            if (doubleClick.click(System.currentTimeMillis())) SwingUtilities.invokeLater { setFullscreen(!gate.fullscreen) }
                        },
                    )
                }
            }
            // A past video's seek bar sits between the picture and the strip, outside the video.
            if (tile.isVod) {
                VodTransportRow(
                    info.player,
                    onTogglePlay = { viewModel.togglePlay(tileId) },
                    onSeek = { viewModel.seekTo(tileId, it) },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PopoutLayout.STRIP_HEIGHT.dp)
                    .background(c.surfaceContainer)
                    .windowDrag(awtWindow, enabled = !fullscreen, onClick = { viewModel.setActive(tileId) }, onDoubleClick = { setFullscreen(!gate.fullscreen) })
                    .padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Avatar(displayName = info.displayName, imageUrl = info.avatarUrl, size = 28)
                TooltipArea(
                    tooltip = {
                        Text(
                            if (tile.isVod) PopoutShortcuts.VOD_HINT else PopoutShortcuts.HINT,
                            style = MaterialTheme.typography.labelSmall,
                            color = c.onSurface,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.surfaceHigh).padding(8.dp),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Column {
                        Text(tile.vod?.title?.ifBlank { null } ?: info.displayName, style = MaterialTheme.typography.titleSmall, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = when {
                            info.message != null -> info.message
                            audible -> "You are listening"
                            tile.silent -> if (active) "Selected, sound off" else "Sound off"
                            info.isBuffering -> "Loading..."
                            active -> "Selected"
                            else -> "Click to select"
                        }
                        Text(
                            sub,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (info.message == null && (audible || active)) c.primary else c.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!tile.isVod && (info.adBlockStatus != AdBlockStatus.UNKNOWN || info.player != null)) AdBlockPill(info.adBlockStatus)
                ExpressiveIconButton(
                    icon = if (tile.silent) ExpressiveIcons.VolumeOff else ExpressiveIcons.VolumeUp,
                    contentDescription = if (tile.silent) "Unmute ${info.displayName}" else "Mute ${info.displayName}",
                    onClick = { viewModel.toggleMute(tileId) },
                    boxSize = 36.dp,
                    iconSize = 18.dp,
                )
                ExpressiveSlider(
                    value = if (tile.silent) 0f else tile.volume / 100f,
                    onValueChange = { viewModel.setVolume(tileId, (it * 100f).roundToInt()) },
                    modifier = Modifier.width(72.dp),
                )
                ExpressiveIconButton(
                    icon = ExpressiveIcons.Chat,
                    contentDescription = "Chat, in the main PureTV window",
                    onClick = { session.requestGrid() },
                    boxSize = 36.dp,
                    iconSize = 18.dp,
                )
                ExpressiveIconButton(
                    icon = ExpressiveIcons.Add,
                    contentDescription = "Add another channel",
                    onClick = { session.requestGrid() },
                    boxSize = 36.dp,
                    iconSize = 18.dp,
                    enabled = layout.canAdd,
                )
                ExpressiveIconButton(
                    icon = ExpressiveIcons.Grid,
                    contentDescription = "Dock back into multi-stream",
                    onClick = ::dockBack,
                    boxSize = 36.dp,
                    iconSize = 18.dp,
                )
                ExpressiveIconButton(
                    icon = if (fullscreen) ExpressiveIcons.FullscreenExit else ExpressiveIcons.Fullscreen,
                    contentDescription = if (fullscreen) "Leave fullscreen" else "Fullscreen on this monitor",
                    onClick = { setFullscreen(!fullscreen) },
                    boxSize = 36.dp,
                    iconSize = 18.dp,
                )
                ExpressiveIconButton(
                    icon = ExpressiveIcons.Close,
                    contentDescription = "Close ${info.displayName}",
                    onClick = { session.closeTile(tileId) },
                    boxSize = 36.dp,
                    iconSize = 18.dp,
                )
            }
        }
    }
}

/**
 * Drags the window while the pointer is held on this element and calls [onDoubleClick] for a
 * double click. A press a button or the slider already took is left alone, and a plain click
 * (not a drag) calls [onClick]. The drag is handed to Windows once the pointer travels past
 * the touch slop, so snapping and moving across monitors work like any other window.
 */
private fun Modifier.windowDrag(
    window: java.awt.Window,
    enabled: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
): Modifier = composed {
    val doubleClick = remember { DoubleClickDetector() }
    val latestClick by rememberUpdatedState(onClick)
    val latestDouble by rememberUpdatedState(onDoubleClick)
    val latestEnabled by rememberUpdatedState(enabled)
    pointerInput(window) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.none { it.pressed }) break
                if (event.changes.any { it.isConsumed }) return@awaitEachGesture
                val travelled = (event.changes.first().position - down.position).getDistance()
                if (latestEnabled && event.type == PointerEventType.Move && travelled > viewConfiguration.touchSlop) {
                    // Blocks in the OS move loop until the button is released (Windows).
                    if (WindowsNative.startWindowDrag(window)) return@awaitEachGesture
                    // Elsewhere there is no OS move loop to hand over to: follow the pointer.
                    val start = java.awt.MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
                    val origin = window.location
                    while (true) {
                        val move = awaitPointerEvent()
                        if (move.changes.none { it.pressed }) break
                        val p = java.awt.MouseInfo.getPointerInfo()?.location ?: break
                        window.setLocation(origin.x + p.x - start.x, origin.y + p.y - start.y)
                    }
                    return@awaitEachGesture
                }
            }
            latestClick()
            if (doubleClick.click(System.currentTimeMillis())) latestDouble()
        }
    }
}
