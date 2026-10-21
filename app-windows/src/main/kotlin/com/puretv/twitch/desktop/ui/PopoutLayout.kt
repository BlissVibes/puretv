package com.puretv.twitch.desktop.ui

import com.puretv.twitch.desktop.data.AtomicFile
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.io.File
import kotlin.math.roundToInt

/**
 * Where popped-out stream windows open, with no AWT in it so it can be tested. A popped
 * window is a 16:9 video with a [STRIP_HEIGHT] control strip under it. Rectangles are in
 * AWT screen units across the whole virtual desktop, so a monitor to the left of or above
 * the primary one has negative coordinates.
 */
object PopoutLayout {
    const val STRIP_HEIGHT = 48
    const val MIN_WIDTH = 560
    const val DEFAULT_WIDTH = 960

    /** Gap kept to a screen edge, and between windows sharing one screen. */
    const val MARGIN = 24
    private const val GAP = 12

    /** Each further window opened on the same screen is shifted this far, so none hides another. */
    private const val CASCADE = 32
    private const val CASCADE_STEPS = 6

    fun heightFor(width: Int): Int = (width * 9 / 16.0).roundToInt() + STRIP_HEIGHT

    fun widthFor(height: Int): Int = ((height - STRIP_HEIGHT) * 16 / 9.0).roundToInt()

    /** The largest 16:9 window that fits inside [cell], centred in it. */
    fun fitIn(cell: MiniRect): MiniRect {
        val w = minOf(cell.width, widthFor(cell.height)).coerceAtLeast(1)
        val h = minOf(heightFor(w), cell.height).coerceAtLeast(1)
        return MiniRect(cell.x + (cell.width - w) / 2, cell.y + (cell.height - h) / 2, w, h)
    }

    /**
     * The order monitors are filled in: left to right, then top to bottom, with the one at
     * [avoid] (the monitor the main window is on) last, so a popped stream does not land on
     * top of the window the viewer keeps browsing in. Returns indexes into the work areas.
     */
    fun monitorOrder(works: List<MiniRect>, avoid: Int?, occupied: List<MiniRect> = emptyList()): List<Int> {
        val sorted = works.indices.sortedWith(compareBy({ works[it].x }, { works[it].y }))
        // Monitors that already hold a popped-out window come after the free ones.
        val busy = occupied.mapNotNull { monitorOf(it, works) }.toSet()
        val free = sorted.filter { it != avoid && it !in busy }
        return free + sorted.filter { it != avoid && it in busy } + sorted.filter { it == avoid }
    }

    /**
     * One window per tile for "Pop out all": tile 0, 1, 2... go to monitor 0, 1, 2... of
     * [monitorOrder], wrapping round when there are more tiles than monitors. A monitor
     * with one window gets the biggest 16:9 window that fits; a monitor that has to share
     * is tiled (two side by side, three or four in a 2x2 grid). Every result lies fully
     * inside its monitor's work area, so none can start out of reach. [occupied] are the
     * windows already popped out: monitors holding one are filled after the free ones.
     * Empty when there are no monitors to place on.
     */
    fun assign(count: Int, works: List<MiniRect>, avoid: Int? = null, occupied: List<MiniRect> = emptyList()): List<MiniRect> {
        if (count <= 0 || works.isEmpty()) return emptyList()
        val order = monitorOrder(works, avoid, occupied)
        val perMonitor = LinkedHashMap<Int, MutableList<Int>>()
        for (tile in 0 until count) perMonitor.getOrPut(order[tile % order.size]) { ArrayList() }.add(tile)
        val result = arrayOfNulls<MiniRect>(count)
        for ((monitor, tiles) in perMonitor) {
            val area = inset(works[monitor], MARGIN)
            val shape = MultiViewLayout.shape(tiles.size)
            val cellW = ((area.width - GAP * (shape.columns - 1)) / shape.columns).coerceAtLeast(1)
            val cellH = ((area.height - GAP * (shape.rows - 1)) / shape.rows).coerceAtLeast(1)
            tiles.forEachIndexed { i, tile ->
                val cell = MultiViewLayout.cell(i, tiles.size)
                val w = cellW * cell.columnSpan + GAP * (cell.columnSpan - 1)
                val rect = MiniRect(area.x + cell.column * (cellW + GAP), area.y + cell.row * (cellH + GAP), w, cellH)
                result[tile] = fitIn(rect)
            }
        }
        return result.map { it!! }
    }

    /**
     * Where one newly popped-out window opens: on a monitor other than the one at [avoid]
     * when there is one, otherwise on the only monitor. [openCount] windows are already up,
     * and each shifts the next a little so they cascade instead of stacking exactly.
     */
    fun placeOne(works: List<MiniRect>, avoid: Int?, openCount: Int): MiniRect? {
        if (works.isEmpty()) return null
        val work = works[monitorOrder(works, avoid).first()]
        val area = inset(work, MARGIN)
        val w = minOf(DEFAULT_WIDTH, area.width, widthFor(area.height)).coerceAtLeast(1)
        val h = minOf(heightFor(w), area.height)
        val shift = (openCount % CASCADE_STEPS) * CASCADE
        val centred = MiniRect(area.x + (area.width - w) / 2 + shift, area.y + (area.height - h) / 2 + shift, w, h)
        return MiniPlayerGeometry.clampToWork(centred, work)
    }

    /**
     * The bounds to open a window with: the spot remembered for it when some of it still
     * shows on a monitor (rescued onto the nearest one when its monitor is gone),
     * otherwise [fallback]. Never smaller than [MIN_WIDTH] wide.
     */
    fun resolve(saved: MiniRect?, fallback: MiniRect, works: List<MiniRect>): MiniRect {
        if (saved == null || works.isEmpty()) return fallback
        val sized = saved.copy(width = saved.width.coerceAtLeast(MIN_WIDTH), height = saved.height.coerceAtLeast(heightFor(MIN_WIDTH)))
        return MiniPlayerGeometry.rescueIfOffscreen(sized, works)
    }

    private fun inset(work: MiniRect, by: Int): MiniRect =
        if (work.width > by * 4 && work.height > by * 4) MiniRect(work.x + by, work.y + by, work.width - by * 2, work.height - by * 2) else work

    /** The work area (screen minus taskbar) of every monitor, in virtual desktop coordinates. */
    fun screenWorkAreas(): List<MiniRect> =
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { workArea(it.defaultConfiguration) }

    fun workArea(gc: GraphicsConfiguration): MiniRect {
        val b = gc.bounds
        val i = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        return MiniRect(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
    }

    /** The index in [works] of the monitor holding the centre of [window], or null. */
    fun monitorOf(window: MiniRect, works: List<MiniRect>): Int? =
        works.indices.firstOrNull { works[it].contains(window.centerX, window.centerY) }
}

/**
 * Decides whether a popped-out window's move or resize is a place worth remembering. It is
 * not while the window is fullscreen or maximized, and the flag is set before the bounds are
 * changed (and cleared only after they are put back), because the resize events arrive
 * after the change, when a composed flag would not have caught up yet.
 */
class PopoutSaveGate {
    @Volatile var fullscreen = false

    fun shouldSave(maximized: Boolean): Boolean = !fullscreen && !maximized
}

/**
 * Remembers where each channel's popped-out window was left, by channel login, in
 * `popout-windows.txt` next to the other desktop stores (one line per channel:
 * "login x y width height"). Held in memory for the session and written when a window
 * closes. Anything unreadable is simply forgotten.
 */
class PopoutBoundsStore(
    private val file: File = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PureTwitch/popout-windows.txt"),
) {
    private val lock = Any()
    private var bounds: MutableMap<String, MiniRect>? = null

    private fun map(): MutableMap<String, MiniRect> = bounds ?: load().also { bounds = it }

    private fun load(): MutableMap<String, MiniRect> {
        val loaded = LinkedHashMap<String, MiniRect>()
        runCatching {
            file.readLines().forEach { line ->
                val parts = line.trim().split(' ')
                if (parts.size != 5) return@forEach
                val nums = parts.drop(1).map { it.toIntOrNull() ?: return@forEach }
                val (x, y, w, h) = nums
                // A hand-edited or damaged file must not place a window absurdly.
                if (w in 1..MAX_SANE && h in 1..MAX_SANE && x in -MAX_SANE..MAX_SANE && y in -MAX_SANE..MAX_SANE) {
                    loaded[parts[0]] = MiniRect(x, y, w, h)
                }
            }
        }
        return loaded
    }

    fun recall(login: String): MiniRect? = synchronized(lock) { map()[login.lowercase()] }

    fun remember(login: String, rect: MiniRect) {
        synchronized(lock) {
            val m = map()
            m.remove(login.lowercase())
            m[login.lowercase()] = rect
            // Only the most recent channels are worth keeping.
            while (m.size > MAX_KEPT) m.remove(m.keys.first())
        }
    }

    fun flush() {
        val text = synchronized(lock) {
            bounds?.entries?.joinToString("\n") { (login, r) -> "$login ${r.x} ${r.y} ${r.width} ${r.height}" }
        } ?: return
        runCatching {
            file.parentFile?.mkdirs()
            AtomicFile.writeTextAtomically(file, text)
        }
    }

    private companion object {
        const val MAX_SANE = 100_000
        const val MAX_KEPT = 32
    }
}
