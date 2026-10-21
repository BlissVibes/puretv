package com.puretv.twitch.desktop.ui

import com.puretv.twitch.desktop.data.AtomicFile
import java.awt.Color
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JPanel
import javax.swing.JWindow
import kotlin.math.abs
import kotlin.math.roundToInt

/** A rectangle in AWT screen units (the same units as window bounds and screen insets). */
data class MiniRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2
    fun contains(px: Int, py: Int): Boolean = px >= x && px < x + width && py >= y && py < y + height

    /** The part of this rectangle that lies inside [other], or null when they do not touch. */
    fun intersect(other: MiniRect): MiniRect? {
        val left = maxOf(x, other.x)
        val top = maxOf(y, other.y)
        val right = minOf(x + width, other.x + other.width)
        val bottom = minOf(y + height, other.y + other.height)
        return if (right > left && bottom > top) MiniRect(left, top, right - left, bottom - top) else null
    }
}

/**
 * The mini player's geometry, with no AWT in it so it can be tested. The mini player is
 * the PureTV window itself, shrunk: a 16:9 video with a fixed control strip under it, so
 * the window is always `width x (width * 9 / 16 + STRIP_HEIGHT)`.
 *
 * All sizes are AWT units, which is also what Compose calls dp, so [STRIP_HEIGHT] matches
 * the strip the screen draws.
 */
object MiniPlayerGeometry {
    const val STRIP_HEIGHT = 44
    const val DEFAULT_WIDTH = 480
    const val MIN_WIDTH = 320
    const val MAX_WIDTH = 960

    /** Gap kept to the screen edge when the window is first placed in the corner. */
    const val MARGIN = 16

    /** How much of the window must stay on some screen, each way, for it to be reachable. */
    const val MIN_VISIBLE = 64

    /** The window height that gives a 16:9 video above the strip. */
    fun heightFor(width: Int): Int = (width * 9 / 16.0).roundToInt() + STRIP_HEIGHT

    /** The window width whose 16:9 video fills [height] minus the strip. */
    fun widthFor(height: Int): Int = ((height - STRIP_HEIGHT) * 16 / 9.0).roundToInt()

    /** The widest mini window that fits [work], and the narrowest (never wider than the widest). */
    private fun widthRange(work: MiniRect): IntRange {
        val hi = minOf(MAX_WIDTH, work.width, widthFor(work.height)).coerceAtLeast(1)
        return minOf(MIN_WIDTH, hi)..hi
    }

    fun clampWidth(width: Int, work: MiniRect): Int {
        val range = widthRange(work)
        return width.coerceIn(range.first, range.last)
    }

    /** Bottom-right of [work], [MARGIN] in from the taskbar and the screen edge. */
    fun defaultBounds(work: MiniRect, width: Int = DEFAULT_WIDTH): MiniRect {
        val w = clampWidth(width, work)
        val h = heightFor(w)
        return clampToWork(MiniRect(work.x + work.width - w - MARGIN, work.y + work.height - h - MARGIN, w, h), work)
    }

    /** Moves [bounds] (never resizes it) so it sits fully inside [work]. */
    fun clampToWork(bounds: MiniRect, work: MiniRect): MiniRect {
        val x = bounds.x.coerceIn(work.x, maxOf(work.x, work.x + work.width - bounds.width))
        val y = bounds.y.coerceIn(work.y, maxOf(work.y, work.y + work.height - bounds.height))
        return bounds.copy(x = x, y = y)
    }

    /**
     * True when enough of [bounds] shows on some screen in [works] (the work areas of every
     * monitor, in virtual desktop coordinates) for the viewer to grab it and drag it back.
     */
    fun isReachable(bounds: MiniRect, works: List<MiniRect>): Boolean =
        works.any { work ->
            val visible = bounds.intersect(work)
            visible != null && visible.width >= MIN_VISIBLE && visible.height >= MIN_VISIBLE
        }

    /**
     * Leaves [bounds] alone wherever it sits across the monitors, and only when it has been
     * lost (not [isReachable]) moves it fully onto the nearest screen. Never resizes.
     */
    fun rescueIfOffscreen(bounds: MiniRect, works: List<MiniRect>): MiniRect {
        if (works.isEmpty() || isReachable(bounds, works)) return bounds
        val nearest = works.minBy { work ->
            val dx = (work.centerX - bounds.centerX).toLong()
            val dy = (work.centerY - bounds.centerY).toLong()
            dx * dx + dy * dy
        }
        return clampToWork(bounds, nearest)
    }

    /**
     * Where the window opens: the remembered [saved] spot when it still shows on a screen in
     * [works] (on whichever monitor it was left, and rescued if that monitor shrank away),
     * otherwise the corner of [current].
     */
    fun initialBounds(saved: MiniRect?, works: List<MiniRect>, current: MiniRect): MiniRect {
        if (saved != null) {
            val host = works.firstOrNull { it.contains(saved.centerX, saved.centerY) }
                ?: works.firstOrNull { saved.intersect(it) != null }
            if (host != null) {
                val w = clampWidth(saved.width, host)
                return rescueIfOffscreen(MiniRect(saved.x, saved.y, w, heightFor(w)), works)
            }
        }
        return defaultBounds(current)
    }

    /**
     * The size to settle on after the window was resized to [newW] x [newH] from
     * [lastW] x [lastH], keeping 16:9. Whichever side the viewer moved drives the other;
     * if both changed (a corner), the width does.
     */
    fun fitSize(newW: Int, newH: Int, lastW: Int, lastH: Int, work: MiniRect): Pair<Int, Int> {
        val heightOnly = newH != lastH && newW == lastW
        val w = clampWidth(if (heightOnly) widthFor(newH) else newW, work)
        return w to heightFor(w)
    }

    /**
     * The window to settle on after the viewer dragged an edge, from [prev] to [now]. Sized
     * by [fitSize] against [work], the screen it is on; an edge that did not move stays where
     * it was, so dragging the left or top edge keeps the right or bottom edge still instead
     * of the window creeping away. The result is only rescued onto a screen in [works] if
     * it was lost, so a window left across two monitors stays there.
     */
    fun resizeAnchored(prev: MiniRect, now: MiniRect, work: MiniRect, works: List<MiniRect> = listOf(work)): MiniRect {
        val (w, h) = fitSize(now.width, now.height, prev.width, prev.height, work)
        val x = if (now.x != prev.x) prev.x + prev.width - w else now.x
        val y = if (now.y != prev.y) prev.y + prev.height - h else now.y
        return rescueIfOffscreen(MiniRect(x, y, w, h), works)
    }
}

/** Counts two clicks inside [windowMs] as a double click, for the native video surface. */
class DoubleClickDetector(private val windowMs: Long = 400) {
    private var last = Long.MIN_VALUE

    /** Records a click at [nowMs]; true when it completes a double click. */
    fun click(nowMs: Long): Boolean {
        val double = last != Long.MIN_VALUE && nowMs - last in 0..windowMs
        last = if (double) Long.MIN_VALUE else nowMs
        return double
    }
}

/**
 * Remembers where the mini player was left, in `mini-player.txt` next to the other desktop
 * stores: "x y width". Anything unreadable is simply forgotten.
 */
class MiniBoundsStore(
    private val file: File = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PureTwitch/mini-player.txt"),
) {
    fun load(): MiniRect? = runCatching {
        val (x, y, w) = file.readText().trim().split(' ').map { it.toInt() }
        MiniRect(x, y, w, MiniPlayerGeometry.heightFor(w))
    }.getOrNull()

    fun save(bounds: MiniRect) {
        runCatching {
            file.parentFile?.mkdirs()
            AtomicFile.writeTextAtomically(file, "${bounds.x} ${bounds.y} ${bounds.width}")
        }
    }
}

/**
 * Puts the PureTV window into, and takes it out of, the mini player: always on top, a
 * small 16:9 window in a corner. It only moves and resizes the window; the shell captures
 * and restores the previous geometry (see [AppShellController]), so nothing about the video
 * surface changes and playback never rebuffers.
 */
class MiniPlayerController(
    private val window: Window,
    private val store: MiniBoundsStore = MiniBoundsStore(),
    /** Called when the window gets maximized or snapped full while in the mini player. */
    private val onMaximized: () -> Unit = {},
    /** Called when the video is double clicked. */
    private val onVideoDoubleClick: () -> Unit = {},
) {
    private var active = false
    private var wasAlwaysOnTop = false
    private var previousMinimum: Dimension? = null
    private var last = MiniRect(0, 0, 0, 0)
    private var settling = false
    private var glass: VideoGlass? = null
    private var dragWindowX = 0
    private var dragWindowY = 0
    private var dragPointerX = 0
    private var dragPointerY = 0

    private fun bounds() = MiniRect(window.x, window.y, window.width, window.height)

    // Keeps 16:9 while the viewer drags an edge of the window, holding the opposite edge still.
    private val keepAspect = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
            if (settling) return
            val work = workAreaOf(window) ?: return
            val fixed = MiniPlayerGeometry.resizeAnchored(last, bounds(), work, allWorkAreas())
            settling = true
            try {
                if (fixed != bounds()) window.setBounds(fixed.x, fixed.y, fixed.width, fixed.height)
                last = fixed
            } finally {
                settling = false
            }
        }

        // A plain move (the size did not change) is the new resting place; a move that comes
        // with a resize is an edge being dragged and is read by componentResized instead.
        override fun componentMoved(e: ComponentEvent) {
            if (settling) return
            val now = bounds()
            if (now.width == last.width && now.height == last.height) last = now
        }
    }

    private val watchMaximize = java.awt.event.WindowStateListener { e ->
        if (e.newState and Frame.MAXIMIZED_BOTH != 0) onMaximized()
    }

    fun enter() {
        if (active) return
        active = true
        wasAlwaysOnTop = window.isAlwaysOnTop
        previousMinimum = window.minimumSize
        val works = allWorkAreas()
        val screen = Toolkit.getDefaultToolkit().screenSize
        val current = workAreaOf(window) ?: works.firstOrNull() ?: MiniRect(0, 0, screen.width, screen.height)
        val target = MiniPlayerGeometry.initialBounds(store.load(), works, current)
        settling = true
        try {
            window.minimumSize = Dimension(MiniPlayerGeometry.MIN_WIDTH, MiniPlayerGeometry.heightFor(MiniPlayerGeometry.MIN_WIDTH))
            window.setBounds(target.x, target.y, target.width, target.height)
            window.isAlwaysOnTop = true
        } finally {
            settling = false
        }
        last = target
        window.addComponentListener(keepAspect)
        (window as? Frame)?.addWindowStateListener(watchMaximize)
        glass = VideoGlass(window, this, onVideoDoubleClick).also { it.show() }
    }

    /** Leaves the mini window where it is on screen; the shell restores the old geometry. */
    fun exit() {
        if (!active) return
        active = false
        glass?.dispose()
        glass = null
        window.removeComponentListener(keepAspect)
        (window as? Frame)?.removeWindowStateListener(watchMaximize)
        if ((window as? Frame)?.extendedState?.and(Frame.MAXIMIZED_BOTH) == 0) store.save(bounds())
        window.isAlwaysOnTop = wasAlwaysOnTop
        window.minimumSize = previousMinimum
    }

    /** A drag begins with the pointer at [pointerX], [pointerY] on screen. */
    fun startDrag(pointerX: Int, pointerY: Int) {
        dragWindowX = window.x
        dragWindowY = window.y
        dragPointerX = pointerX
        dragPointerY = pointerY
    }

    /**
     * The window follows the pointer, which is read in screen units: those are the same on
     * every monitor and DPI, and unlike deltas inside the window they do not drift while the
     * window moves under the pointer.
     */
    fun dragTo(pointerX: Int, pointerY: Int) =
        window.setLocation(dragWindowX + pointerX - dragPointerX, dragWindowY + pointerY - dragPointerY)

    /**
     * After a drag: the window stays wherever it was dropped, on any monitor, and is only
     * moved when it was dropped where it can no longer be reached. The spot is remembered.
     */
    fun settle() {
        val fixed = MiniPlayerGeometry.rescueIfOffscreen(bounds(), allWorkAreas())
        if (fixed != bounds()) window.setLocation(fixed.x, fixed.y)
        last = bounds()
        if (active) store.save(last)
    }

    private fun allWorkAreas(): List<MiniRect> =
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { workArea(it.defaultConfiguration) }

    /** The work area of the screen holding the window's centre, else the window's own screen. */
    private fun workAreaOf(w: Window): MiniRect? {
        val cx = w.x + w.width / 2
        val cy = w.y + w.height / 2
        return allWorkAreas().firstOrNull { it.contains(cx, cy) } ?: w.graphicsConfiguration?.let(::workArea)
    }

    private fun workArea(gc: GraphicsConfiguration): MiniRect {
        val b = gc.bounds
        val i = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        return MiniRect(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
    }
}

/**
 * A see-through window laid over the mini player's video. The video is a native window
 * (the player draws into its own child window) that takes the mouse before anything else
 * sees it, so the video itself can neither be dragged nor clicked. This sits above it,
 * nearly invisible but still hit by the mouse, and turns a press-drag into moving the mini
 * window and a double click into [onDoubleClick]. A margin is left clear so the edges keep
 * resizing, and the control strip is not covered.
 */
private class VideoGlass(
    private val mini: Window,
    private val controller: MiniPlayerController,
    private val onDoubleClick: () -> Unit,
) {
    private val glass = JWindow(mini).apply {
        focusableWindowState = false
        background = Color(0, 0, 0, 1)
        contentPane = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                g.color = Color(0, 0, 0, 1)
                g.fillRect(0, 0, width, height)
            }
        }.apply { isOpaque = false }
        isAlwaysOnTop = true
    }
    private val doubleClick = DoubleClickDetector()
    private var pressX = 0
    private var pressY = 0
    private var dragging = false

    private val follow = object : ComponentAdapter() {
        override fun componentMoved(e: ComponentEvent) = place()
        override fun componentResized(e: ComponentEvent) = place()
    }

    private val mouse = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            pressX = e.xOnScreen
            pressY = e.yOnScreen
            dragging = false
            controller.startDrag(pressX, pressY)
        }

        override fun mouseDragged(e: MouseEvent) {
            if (!dragging && abs(e.xOnScreen - pressX) + abs(e.yOnScreen - pressY) <= DRAG_SLOP) return
            dragging = true
            controller.dragTo(e.xOnScreen, e.yOnScreen)
        }

        override fun mouseReleased(e: MouseEvent) {
            if (dragging) controller.settle() else if (doubleClick.click(System.currentTimeMillis())) onDoubleClick()
            dragging = false
        }
    }

    init {
        glass.addMouseListener(mouse)
        glass.addMouseMotionListener(mouse)
    }

    /** Covers the video, minus the resize margin, and follows the mini window from here on. */
    fun show() {
        place()
        glass.isVisible = true
        mini.addComponentListener(follow)
    }

    fun dispose() {
        mini.removeComponentListener(follow)
        glass.dispose()
    }

    private fun place() {
        glass.setBounds(
            mini.x + EDGE,
            mini.y + EDGE,
            (mini.width - 2 * EDGE).coerceAtLeast(1),
            (mini.height - MiniPlayerGeometry.STRIP_HEIGHT - EDGE).coerceAtLeast(1),
        )
    }

    private companion object {
        /** Margin left to the window edges, so they still resize. */
        const val EDGE = 8

        /** Pointer travel, in screen units, before a press counts as a drag. */
        const val DRAG_SLOP = 4
    }
}
