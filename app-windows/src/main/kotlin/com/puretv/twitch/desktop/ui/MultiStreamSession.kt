package com.puretv.twitch.desktop.ui

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** What the session needs from the tiles it holds; [MultiViewViewModel] is the real one. */
interface MultiTileHost {
    val state: StateFlow<MultiViewUiState>

    /** False when [login] is already shown, blank, or the tiles are full. */
    fun addChannel(login: String): Boolean

    /** False when the video is already shown, or the tiles are full. */
    fun addVod(source: TileSource.Vod): Boolean

    /** Closes tile [id] and returns the tiles left. */
    fun closeTile(id: Int): List<MultiTile>

    /** Closes the tiles shown in the grid, leaves the popped-out ones, returns the tiles left. */
    fun closeDocked(): List<MultiTile>

    fun popOut(id: Int)
    fun dock(id: Int)
    fun popOutAll()
    fun dockAll()

    /** Stops and frees everything. */
    fun shutDown()
}

typealias MultiSession = MultiStreamSession<MultiViewViewModel>

/**
 * The one multi-stream session of the app. It lives here, not in the multi-stream screen,
 * so a tile popped out into its own window keeps playing when the main window goes
 * somewhere else. The rules:
 *
 *  - [open] starts the session with a channel, or adds the channel to the session that is
 *    already running.
 *  - [leaveGrid] (the multi-stream screen was left) closes the tiles shown in the grid and
 *    leaves the popped-out ones playing.
 *  - The session ends, freeing everything, when the last tile closes, or at once with [end]
 *    (the sleep timer ran out, PureTV is quitting).
 */
class MultiStreamSession<T : MultiTileHost>(
    private val create: (String) -> T,
    /** The one sleep timer of the app, shared by the stream screen, multi-stream and the popped-out windows. */
    val sleep: SleepSession = SleepSession(),
    val boundsStore: PopoutBoundsStore = PopoutBoundsStore(),
    // Starts a session whose first tile is a past video; null when only live sessions can start.
    private val createVod: ((TileSource.Vod) -> T)? = null,
) {
    private val _current = MutableStateFlow<T?>(null)

    /** The running session's tiles, or null when nothing is open. */
    val current: StateFlow<T?> = _current.asStateFlow()

    /** Where the main window is, so popped-out windows open on another monitor when there is one. */
    @Volatile var mainBounds: () -> MiniRect? = { null }

    // Placements chosen by "Pop out all", used once by the window that opens for the tile.
    private val planned = ConcurrentHashMap<Int, MiniRect>()

    // Asks to show the multi-stream screen in the main window (see [requestGrid]).
    private val gridRequests = Channel<Unit>(Channel.CONFLATED)
    val openGrid: Flow<Unit> = gridRequests.receiveAsFlow()

    @Synchronized
    fun open(login: String): T {
        val running = _current.value
        if (running != null) {
            running.addChannel(login)
            return running
        }
        return create(login).also { _current.value = it }
    }

    /**
     * Adds the past video [source] as a tile, starting the session when none is running.
     * Says what happened, so the caller can tell the viewer when nothing was added.
     */
    @Synchronized
    fun addVod(source: TileSource.Vod): AddOutcome {
        val running = _current.value
        if (running == null) {
            val make = createVod ?: return AddOutcome.Blank
            _current.value = make(source)
            return AddOutcome.Added
        }
        val outcome = running.state.value.layout.addVodOutcome(source.videoId)
        if (outcome == AddOutcome.Added) running.addVod(source)
        return outcome
    }

    /**
     * Shows the past video [source] in a window of its own: a new tile that is popped out
     * at once, or the tile already showing it. Nothing happens when the tiles are full.
     */
    @Synchronized
    fun popOutVod(source: TileSource.Vod): AddOutcome {
        val outcome = addVod(source)
        if (outcome != AddOutcome.Added && outcome !is AddOutcome.AlreadyShown) return outcome
        val running = _current.value ?: return outcome
        val tile = running.state.value.layout.tiles.firstOrNull { it.vod?.videoId == source.videoId.trim() } ?: return outcome
        running.popOut(tile.id)
        return outcome
    }

    /** The multi-stream screen was left: only the tiles in their own windows stay. */
    @Synchronized
    fun leaveGrid() {
        val running = _current.value ?: return
        if (running.closeDocked().isEmpty()) end()
    }

    /** Closes tile [id], ending the session when it was the last one. Returns the tiles left. */
    @Synchronized
    fun closeTile(id: Int): List<MultiTile> {
        val running = _current.value ?: return emptyList()
        planned.remove(id)
        val left = running.closeTile(id)
        if (left.isEmpty()) end()
        return left
    }

    @Synchronized
    fun end() {
        val running = _current.value ?: return
        _current.value = null
        planned.clear()
        running.shutDown()
        boundsStore.flush()
    }

    /** Pops tile [id] out into a window of its own. */
    fun popOut(id: Int) {
        _current.value?.popOut(id)
    }

    /** "Pop out all": every tile gets its own window, spread over the monitors. */
    fun popOutAll() {
        val running = _current.value ?: return
        // Only the tiles still in the grid get a new window; the ones already out stay where they are.
        val tiles = running.state.value.layout.docked
        if (tiles.isEmpty()) return
        val works = PopoutLayout.screenWorkAreas()
        val main = mainBounds()
        val open = PoppedWindows.all.filter { it.isShowing }.map { MiniRect(it.x, it.y, it.width, it.height) }
        val places = PopoutLayout.assign(tiles.size, works, main?.let { PopoutLayout.monitorOf(it, works) }, open)
        tiles.forEachIndexed { i, tile -> places.getOrNull(i)?.let { planned[tile.id] = it } }
        running.popOutAll()
    }

    fun dock(id: Int) {
        _current.value?.dock(id)
    }

    fun dockAll() {
        _current.value?.dockAll()
    }

    /** Where the window for tile [id] ([login]) opens: what "Pop out all" planned, else where it was left, else a fresh spot. */
    fun placementFor(id: Int, login: String, openCount: Int): MiniRect? {
        val works = PopoutLayout.screenWorkAreas()
        planned.remove(id)?.let { return it }
        val main = mainBounds()
        val fresh = PopoutLayout.placeOne(works, main?.let { PopoutLayout.monitorOf(it, works) }, openCount) ?: return null
        return PopoutLayout.resolve(boundsStore.recall(login), fresh, works)
    }

    /** From a popped-out window: bring the main window to the multi-stream screen to add or watch more. */
    fun requestGrid() {
        gridRequests.trySend(Unit)
    }
}

/**
 * The popped-out windows that exist right now. The keyboard handlers of the main window
 * stand down while one of them is the active window, so a key pressed there acts on its
 * stream only.
 */
object PoppedWindows {
    private val windows = CopyOnWriteArrayList<Window>()

    fun add(window: Window) {
        windows.addIfAbsent(window)
    }

    fun remove(window: Window) {
        windows.remove(window)
    }

    val all: List<Window> get() = windows.toList()

    /** True when a popped-out window is the active window. */
    fun isActive(): Boolean {
        val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow ?: return false
        return windows.any { it === active }
    }
}
