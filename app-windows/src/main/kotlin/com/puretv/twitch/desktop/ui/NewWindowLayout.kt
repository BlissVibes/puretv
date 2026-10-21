package com.puretv.twitch.desktop.ui

import java.awt.Window

/**
 * Where a new full PureTV window opens, with no AWT in the maths so it can be tested.
 * Rectangles are AWT screen units across the whole virtual desktop, so a monitor to the
 * left of or above the primary one has negative coordinates.
 *
 * It goes on a monitor other than the one the viewer is on when there is one, preferring
 * monitors that hold no PureTV window yet (see [PopoutLayout.monitorOrder]). With one
 * monitor it cascades, each further window a little down and right of the last.
 */
object NewWindowLayout {
    const val DEFAULT_WIDTH = 1280
    const val DEFAULT_HEIGHT = 720
    const val MIN_WIDTH = 900
    const val MIN_HEIGHT = 560

    private const val MARGIN = 24
    private const val CASCADE = 36
    private const val CASCADE_STEPS = 8

    /**
     * The bounds for a window opening now. [avoid] is the index in [works] of the monitor the
     * viewer is working on, [occupied] the bounds of the PureTV windows already open. Null
     * when there are no monitors to place on.
     */
    fun place(works: List<MiniRect>, avoid: Int?, occupied: List<MiniRect>): MiniRect? {
        if (works.isEmpty()) return null
        val monitor = PopoutLayout.monitorOrder(works, avoid, occupied).first()
        val work = works[monitor]
        val area = if (work.width > MARGIN * 4 && work.height > MARGIN * 4) {
            MiniRect(work.x + MARGIN, work.y + MARGIN, work.width - MARGIN * 2, work.height - MARGIN * 2)
        } else work
        val w = minOf(DEFAULT_WIDTH, area.width).coerceAtLeast(1)
        val h = minOf(DEFAULT_HEIGHT, area.height).coerceAtLeast(1)
        // Windows already on this monitor push the new one along the cascade.
        val sharing = occupied.count { PopoutLayout.monitorOf(it, works) == monitor }
        val shift = (sharing % CASCADE_STEPS) * CASCADE
        val centred = MiniRect(area.x + (area.width - w) / 2 + shift, area.y + (area.height - h) / 2 + shift, w, h)
        return MiniPlayerGeometry.clampToWork(centred, work)
    }

    /**
     * The bounds to open with: the spot remembered for this window's slot when some of it
     * still shows on a monitor (rescued onto the nearest one when its monitor is gone),
     * otherwise [fallback]. Never smaller than the minimum window.
     */
    fun resolve(saved: MiniRect?, fallback: MiniRect, works: List<MiniRect>): MiniRect {
        if (saved == null || works.isEmpty()) return fallback
        val sized = saved.copy(width = saved.width.coerceAtLeast(MIN_WIDTH), height = saved.height.coerceAtLeast(MIN_HEIGHT))
        return MiniPlayerGeometry.rescueIfOffscreen(sized, works)
    }
}

/**
 * Whether a window's move or resize is a place worth remembering: not while it is maximized,
 * and not while it is minimized, when Windows parks it at -32000, -32000.
 */
object WindowBoundsGate {
    private const val ICONIFIED = 1 // java.awt.Frame.ICONIFIED
    private const val MAXIMIZED_BOTH = 6 // java.awt.Frame.MAXIMIZED_BOTH
    private const val PARKED_BELOW = -30_000

    fun shouldSave(extendedState: Int, x: Int, y: Int): Boolean =
        extendedState and ICONIFIED == 0 && extendedState and MAXIMIZED_BOTH == 0 && x > PARKED_BELOW && y > PARKED_BELOW
}

/**
 * Decides whether a key press belongs to this window. The AWT keyboard focus manager is
 * global, so every window's shortcut handler sees every key; each one stands down unless
 * its own window (or something it owns, such as a dialog) is the active one. A key typed in
 * a window that is not PureTV's at all is not ours to refuse.
 */
object WindowKeyGuard {
    /**
     * [active] is the window that has the keyboard (null when none), [mine] the window asking,
     * [appWindows] every PureTV window including popped-out ones, [ownerOf] a window's owner.
     */
    fun <W : Any> handles(active: W?, mine: W, appWindows: Collection<W>, ownerOf: (W) -> W?): Boolean {
        if (active == null) return true
        var root: W = active
        var hops = 0
        while (hops++ < MAX_OWNER_DEPTH) root = ownerOf(root) ?: break
        if (root === mine) return true
        return appWindows.none { it === root }
    }

    private const val MAX_OWNER_DEPTH = 16
}

/** Every top-level PureTV window, for [WindowKeyGuard]. */
object AppWindows {
    private val windows = java.util.concurrent.CopyOnWriteArrayList<Window>()

    fun add(window: Window) {
        windows.addIfAbsent(window)
    }

    fun remove(window: Window) {
        windows.remove(window)
    }

    /** The PureTV windows (main and secondary) that exist now. */
    fun all(): List<Window> = windows.toList()

    /**
     * True when a key pressed now is for [mine], a PureTV window. With no window to compare
     * ([mine] null) it only stands down for a popped-out window, as before there were many.
     */
    fun handlesKeys(mine: Window?): Boolean {
        if (mine == null) return !PoppedWindows.isActive()
        val active = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        return WindowKeyGuard.handles(active, mine, windows + PoppedWindows.all) { it.owner }
    }
}
