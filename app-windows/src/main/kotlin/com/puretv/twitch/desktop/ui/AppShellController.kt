package com.puretv.twitch.desktop.ui

import com.puretv.twitch.desktop.platform.WindowChrome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import javax.swing.SwingUtilities

/** Distance, in px, from a screen edge that counts as a "drag-to-snap" drop. */
private const val SNAP_EDGE_PX = 14

/**
 * MINI is the mini player: the window itself shrunk into an always-on-top corner player
 * (see [MiniPlayerController]). It starts only when the viewer asks.
 */
enum class PlayerMode { DEFAULT, THEATER, FULLSCREEN, MINI }

/**
 * Owns window-chrome state (player mode, chat, maximize/minimize/fullscreen).
 *
 * FULLSCREEN: we deliberately do NOT use `WindowPlacement.Fullscreen`. On an
 * undecorated window that combination drives Windows *exclusive* fullscreen,
 * whose Direct3D surface fights the heavyweight VLC video Canvas and leaves a
 * blank/white screen, most reliably when entered from a maximized window.
 *
 * Instead we implement *borderless windowed* fullscreen the way Electron-based
 * apps do: clear the maximized flag and stretch the (already undecorated) frame
 * to cover the whole monitor, taskbar included. No exclusive mode, no surface
 * hand-off, no white flash. On exit we restore the exact pre-fullscreen geometry
 * through [WindowState] so its size/placement memory stays correct.
 */
class AppShellController(
    private val windowState: WindowState,
    private val window: Window,
) {

    private var playerModeState by mutableStateOf(PlayerMode.DEFAULT)
    val playerMode: PlayerMode get() = playerModeState

    private var isChatOpenState by mutableStateOf(true)
    val isChatOpen: Boolean get() = isChatOpenState

    /** Pre-fullscreen geometry, captured on entry and replayed on exit. */
    private data class FullscreenRestore(
        val placement: WindowPlacement,
        val position: WindowPosition,
        val size: androidx.compose.ui.unit.DpSize,
    )

    private var fullscreenRestore: FullscreenRestore? = null

    // Snapping or maximizing the mini window (Win+Up) ends mini mode rather than fight it.
    private val miniPlayer = MiniPlayerController(
        window,
        onMaximized = { SwingUtilities.invokeLater { exitMini() } },
        onVideoDoubleClick = { exitMini() },
        onVideoActivity = { miniActivity() },
    )

    /** The geometry to give back when the mini player closes (the window before it, or before fullscreen). */
    private var miniRestore: FullscreenRestore? = null

    fun setPlayerMode(mode: PlayerMode) {
        val previous = playerModeState
        // From the mini player the only ways on are back to the normal window or staying put.
        if (previous == PlayerMode.MINI && mode != PlayerMode.MINI && mode != PlayerMode.DEFAULT) return
        playerModeState = mode
        when {
            // Fullscreen straight into mini: the window to come back to is the one from
            // before fullscreen, so hand that over instead of restoring it only to shrink it.
            mode == PlayerMode.MINI && previous == PlayerMode.FULLSCREEN -> {
                miniRestore = fullscreenRestore
                fullscreenRestore = null
                enterMini()
            }
            mode == PlayerMode.FULLSCREEN && previous != PlayerMode.FULLSCREEN -> enterFullscreen()
            mode != PlayerMode.FULLSCREEN && previous == PlayerMode.FULLSCREEN -> exitFullscreen()
            mode == PlayerMode.MINI && previous != PlayerMode.MINI -> {
                miniRestore = currentGeometry()
                enterMini()
            }
            mode != PlayerMode.MINI && previous == PlayerMode.MINI -> leaveMiniWindow()
        }
    }

    // A window never moved by the user can still be at PlatformDefault; read where it
    // really is, so restoring puts it back there instead of somewhere new.
    private fun currentGeometry() = FullscreenRestore(
        placement = windowState.placement,
        position = if (windowState.position.isSpecified) windowState.position else WindowPosition(window.x.dp, window.y.dp),
        size = windowState.size,
    )

    private fun enterMini() {
        windowState.placement = WindowPlacement.Floating
        (window as? Frame)?.extendedState = Frame.NORMAL
        miniPlayer.enter()
    }

    private fun leaveMiniWindow() {
        miniPlayer.exit()
        val restore = miniRestore ?: return
        miniRestore = null
        (window as? Frame)?.extendedState = Frame.NORMAL
        windowState.position = restore.position
        windowState.size = restore.size
        windowState.placement = restore.placement
    }

    /** Back to the normal window if the mini player is up; anything else is left alone. */
    fun exitMini() {
        if (playerModeState == PlayerMode.MINI) setPlayerMode(PlayerMode.DEFAULT)
    }

    /** The mini strip moves the window by hand; the controller does the moving. */
    fun startMiniDrag() = miniPlayer.startDrag()
    fun dragMiniTo() = miniPlayer.dragTo()
    fun endMiniDrag() = miniPlayer.settle()

    /** Set by the stream screen: the viewer moved the pointer over the mini player's video. */
    var miniActivity: () -> Unit = {}

    private fun enterFullscreen() {
        // Capture geometry through WindowState (it holds the *floating* size even
        // while maximized, so restore is exact in either case).
        fullscreenRestore = currentGeometry()
        // macOS and Linux: the system's fullscreen, which hides their window frame too.
        if (WindowChrome.nativeFullscreen()) {
            windowState.placement = WindowPlacement.Fullscreen
            return
        }
        windowState.placement = WindowPlacement.Floating
        (window as? Frame)?.let { frame ->
            // Clear MAXIMIZED_BOTH, since a maximized frame ignores setBounds, then
            // cover the full monitor (bounds include the taskbar strip).
            frame.extendedState = Frame.NORMAL
            frame.bounds = frame.graphicsConfiguration.bounds
        }
    }

    private fun exitFullscreen() {
        val restore = fullscreenRestore ?: return
        fullscreenRestore = null
        if (WindowChrome.nativeFullscreen()) {
            // The system puts the window back where it was; sizing it mid-transition fights that.
            windowState.placement = restore.placement
            return
        }
        (window as? Frame)?.extendedState = Frame.NORMAL
        // Replay through WindowState so CMP re-applies geometry and keeps its own
        // size/placement memory consistent (important if restoring to Maximized).
        windowState.position = restore.position
        windowState.size = restore.size
        windowState.placement = restore.placement
    }

    fun toggleChat() { isChatOpenState = !isChatOpenState }

    fun exitImmersive() {
        if (playerMode != PlayerMode.DEFAULT) setPlayerMode(PlayerMode.DEFAULT)
    }

    val isImmersive: Boolean get() = playerMode != PlayerMode.DEFAULT

    // Window chrome helpers used by the custom title bar
    fun minimize() { windowState.isMinimized = true }

    fun toggleMaximize() {
        windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
            WindowPlacement.Floating
        } else {
            WindowPlacement.Maximized
        }
    }

    val isMaximized: Boolean get() = windowState.placement == WindowPlacement.Maximized

    /**
     * DIY Aero Snap, invoked when a title-bar drag is released near a screen edge.
     * Windows' own snap engine never engages for our synthesized (HTCAPTION) move
     * loop, so we replicate the essentials ourselves:
     *   - top edge    → maximize (fill the work area, taskbar visible)
     *   - left edge   → left half
     *   - right edge  → right half
     * [cursor] is the screen-space drop point. No-op when it isn't at an edge.
     */
    fun snapForDrop(cursor: Point) {
        val frame = window as? Frame ?: return
        val gc = screenContaining(cursor) ?: frame.graphicsConfiguration ?: return
        val b = gc.bounds
        when {
            cursor.y <= b.y + SNAP_EDGE_PX -> windowState.placement = WindowPlacement.Maximized
            cursor.x <= b.x + SNAP_EDGE_PX -> tileHalf(frame, gc, left = true)
            cursor.x >= b.x + b.width - SNAP_EDGE_PX -> tileHalf(frame, gc, left = false)
        }
    }

    private fun screenContaining(p: Point): GraphicsConfiguration? =
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration }
            .firstOrNull { it.bounds.contains(p) }

    /** Tiles [frame] to the left or right half of [gc]'s work area (taskbar-aware). */
    private fun tileHalf(frame: Frame, gc: GraphicsConfiguration, left: Boolean) {
        val b = gc.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        val wx = b.x + insets.left
        val wy = b.y + insets.top
        val ww = b.width - insets.left - insets.right
        val wh = b.height - insets.top - insets.bottom
        val halfW = ww / 2
        // Leave any maximized state first, then place the floating half-window.
        frame.extendedState = Frame.NORMAL
        windowState.placement = WindowPlacement.Floating
        frame.setBounds(if (left) wx else wx + halfW, wy, halfW, wh)
    }
}

val LocalAppShell = compositionLocalOf<AppShellController> {
    error("LocalAppShell not provided")
}

@Composable
fun rememberAppShellController(windowState: WindowState, window: Window): AppShellController =
    remember(windowState, window) { AppShellController(windowState, window) }
