package com.puretv.twitch.desktop.ui

import com.puretv.twitch.desktop.data.AtomicFile
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File
import kotlin.math.roundToInt

/** A rectangle in AWT screen units (the same units as window bounds and screen insets). */
data class MiniRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2
    fun contains(px: Int, py: Int): Boolean = px >= x && px < x + width && py >= y && py < y + height
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
     * Where the window opens: the remembered [saved] spot when its centre is still on a
     * screen in [works] (kept inside that screen), otherwise the corner of [current].
     */
    fun initialBounds(saved: MiniRect?, works: List<MiniRect>, current: MiniRect): MiniRect {
        if (saved != null) {
            val work = works.firstOrNull { it.contains(saved.centerX, saved.centerY) }
            if (work != null) {
                val w = clampWidth(saved.width, work)
                return clampToWork(MiniRect(saved.x, saved.y, w, heightFor(w)), work)
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
     * by [fitSize]; an edge that did not move stays where it was, so dragging the left or top
     * edge keeps the right or bottom edge still instead of the window creeping away. The
     * result is kept on [work].
     */
    fun resizeAnchored(prev: MiniRect, now: MiniRect, work: MiniRect): MiniRect {
        val (w, h) = fitSize(now.width, now.height, prev.width, prev.height, work)
        val x = if (now.x != prev.x) prev.x + prev.width - w else now.x
        val y = if (now.y != prev.y) prev.y + prev.height - h else now.y
        return clampToWork(MiniRect(x, y, w, h), work)
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
) {
    private var active = false
    private var wasAlwaysOnTop = false
    private var previousMinimum: Dimension? = null
    private var last = MiniRect(0, 0, 0, 0)
    private var settling = false

    private fun bounds() = MiniRect(window.x, window.y, window.width, window.height)

    // Keeps 16:9 while the viewer drags an edge of the window, holding the opposite edge still.
    private val keepAspect = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
            if (settling) return
            val work = workAreaOf(window) ?: return
            val fixed = MiniPlayerGeometry.resizeAnchored(last, bounds(), work)
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
    }

    /** Leaves the mini window where it is on screen; the shell restores the old geometry. */
    fun exit() {
        if (!active) return
        active = false
        window.removeComponentListener(keepAspect)
        (window as? Frame)?.removeWindowStateListener(watchMaximize)
        if ((window as? Frame)?.extendedState?.and(Frame.MAXIMIZED_BOTH) == 0) store.save(bounds())
        window.isAlwaysOnTop = wasAlwaysOnTop
        window.minimumSize = previousMinimum
    }

    /** After a drag: keeps the whole window on a screen so it can never be lost off the edge. */
    fun settle() {
        val work = workAreaOf(window) ?: return
        val fixed = MiniPlayerGeometry.clampToWork(bounds(), work)
        if (fixed != bounds()) window.setLocation(fixed.x, fixed.y)
        last = bounds()
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
