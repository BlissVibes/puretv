package com.puretv.twitch.desktop.ui

import com.puretv.twitch.core.model.StreamQuality
import com.puretv.twitch.core.model.VideoType
import com.puretv.twitch.desktop.player.DEFAULT_VOLUME
import com.puretv.twitch.desktop.player.TileQuality

/** What kind of past video a [TileSource.Vod] is. */
enum class VodKind(val label: String) {
    PAST_BROADCAST("Past broadcast"),
    HIGHLIGHT("Highlight"),
    UPLOAD("Upload"),
    ;

    companion object {
        fun from(type: VideoType): VodKind = when (type) {
            VideoType.HIGHLIGHT -> HIGHLIGHT
            VideoType.UPLOAD -> UPLOAD
            else -> PAST_BROADCAST
        }
    }
}

/**
 * What a tile plays: a live channel, or a past video (broadcast, highlight or upload). The
 * runtime that starts a tile (MultiViewViewModel.openRuntime), its strip (Tile and
 * PoppedStreamWindow) and the chat follow-the-selected-tile rule switch on the source.
 */
sealed interface TileSource {
    data class Live(val login: String) : TileSource

    /**
     * A past video. [channelLogin] is its channel (a live tile of the same channel may sit
     * beside it); [startPositionMs] is where it starts when nothing better is known, which
     * is where a video moved over from the player screen left off.
     */
    data class Vod(
        val videoId: String,
        val title: String,
        val channelLogin: String,
        val startPositionMs: Long = 0,
        val kind: VodKind = VodKind.PAST_BROADCAST,
        val thumbnailUrl: String = "",
    ) : TileSource
}

/**
 * One stream on the multi-stream screen. [id] stays with the tile for as long as it is
 * open. Each tile has its own [volume] (0 to 100) and [muted] flag, so one stream can be
 * loud while another is quiet.
 */
data class MultiTile(
    val id: Int,
    val login: String,
    val volume: Int = DEFAULT_VOLUME,
    val muted: Boolean = false,
    val source: TileSource = TileSource.Live(login),
) {
    /** True when this tile makes no sound by its own settings, whatever is selected. */
    val silent: Boolean get() = muted || volume <= 0

    val vod: TileSource.Vod? get() = source as? TileSource.Vod
    val isVod: Boolean get() = source is TileSource.Vod

    /** The channel when this tile is live, else null: what live-only services (chat, ad block) are keyed by. */
    val liveLogin: String? get() = (source as? TileSource.Live)?.login

    /** The key a popped-out window's place is remembered by. No spaces: the store is space separated. */
    val placeKey: String get() = vod?.let { "video-${it.videoId}" } ?: login
}

/**
 * Which channels the multi-stream screen shows and which one is active (the one you
 * hear, outlined, with its chat open). Pure, so every rule is unit tested.
 *
 * Two to four tiles: two sit side by side, three or four make a 2x2 grid. The
 * screen is opened from a channel that is already playing, so it starts with one
 * tile and the viewer adds the others.
 */
data class MultiViewState(
    val tiles: List<MultiTile> = emptyList(),
    val activeId: Int? = null,
    val nextId: Int = 1,
    /**
     * False: only the selected tile can be heard. True: every tile that is not muted
     * plays at its own volume. Either way no stream reloads, only the sound changes.
     */
    val listenAll: Boolean = false,
    /**
     * Tiles that live in a window of their own (see [PopoutLayout]) instead of the grid.
     * They stay in [tiles], so their sound, selection and quality count as before.
     */
    val poppedIds: Set<Int> = emptySet(),
) {
    val count: Int get() = tiles.size
    val canAdd: Boolean get() = tiles.size < MAX_TILES

    /** The tiles shown in the grid, in grid order. */
    val docked: List<MultiTile> get() = tiles.filter { it.id !in poppedIds }

    /** The tiles that have their own window. */
    val popped: List<MultiTile> get() = tiles.filter { it.id in poppedIds }

    fun isPopped(id: Int): Boolean = id in poppedIds
    val active: MultiTile? get() = tiles.firstOrNull { it.id == activeId }

    /** True when a live tile of [login] is shown. A past video of that channel does not count. */
    fun contains(login: String): Boolean = tiles.any { it.liveLogin.equals(login, ignoreCase = true) }

    /** True when the past video [videoId] is shown. */
    fun containsVod(videoId: String): Boolean = tiles.any { it.vod?.videoId == videoId }

    /** What adding [login] would do, so a refused add can say why instead of doing nothing. */
    fun addOutcome(login: String): AddOutcome {
        val clean = login.trim().removePrefix("@").lowercase()
        return when {
            clean.isEmpty() -> AddOutcome.Blank
            contains(clean) -> AddOutcome.AlreadyShown(tiles.first { it.liveLogin.equals(clean, ignoreCase = true) }.id)
            !canAdd -> AddOutcome.Full
            else -> AddOutcome.Added
        }
    }

    /**
     * The state with [login] added as the last tile. The first tile becomes active;
     * after that the active tile stays, so adding a channel never moves the sound.
     * A channel already on screen, a blank name, or a full screen change nothing. The
     * new tile starts at [volume], which is never 0 so a new stream is never silent.
     */
    fun add(login: String, volume: Int = DEFAULT_VOLUME): MultiViewState {
        val clean = login.trim().removePrefix("@").lowercase()
        if (clean.isEmpty() || contains(clean) || !canAdd) return this
        val tile = MultiTile(nextId, clean, volume = volume.coerceIn(1, 100))
        return copy(tiles = tiles + tile, activeId = activeId ?: tile.id, nextId = nextId + 1)
    }

    /** What adding the past video [videoId] would do (see [addOutcome]). */
    fun addVodOutcome(videoId: String): AddOutcome {
        val clean = videoId.trim()
        return when {
            clean.isEmpty() -> AddOutcome.Blank
            containsVod(clean) -> AddOutcome.AlreadyShown(tiles.first { it.vod?.videoId == clean }.id)
            !canAdd -> AddOutcome.Full
            else -> AddOutcome.Added
        }
    }

    /**
     * The state with the past video [source] added as the last tile, with the same rules
     * as [add]: the first tile becomes active, a video already on screen or a full screen
     * change nothing, and the new tile is never silent.
     */
    fun addVod(source: TileSource.Vod, volume: Int = DEFAULT_VOLUME): MultiViewState {
        if (addVodOutcome(source.videoId) != AddOutcome.Added) return this
        val clean = source.copy(
            videoId = source.videoId.trim(),
            channelLogin = source.channelLogin.trim().removePrefix("@").lowercase(),
            startPositionMs = source.startPositionMs.coerceAtLeast(0),
        )
        val tile = MultiTile(nextId, clean.channelLogin, volume = volume.coerceIn(1, 100), source = clean)
        return copy(tiles = tiles + tile, activeId = activeId ?: tile.id, nextId = nextId + 1)
    }

    /**
     * The state without tile [id]. When the active tile closes, the sound moves to
     * the tile that took its place (or the one before it, when it was last).
     */
    fun remove(id: Int): MultiViewState {
        val index = tiles.indexOfFirst { it.id == id }
        if (index < 0) return this
        val rest = tiles.filterNot { it.id == id }
        val nextActive = when {
            activeId != id -> activeId
            rest.isEmpty() -> null
            else -> rest[index.coerceAtMost(rest.lastIndex)].id
        }
        return copy(tiles = rest, activeId = nextActive, poppedIds = poppedIds - id)
    }

    /** The state with tile [id] moved out of the grid into a window of its own. */
    fun popOut(id: Int): MultiViewState =
        if (tiles.any { it.id == id } && id !in poppedIds) copy(poppedIds = poppedIds + id) else this

    /** The state with tile [id] back in the grid. */
    fun dock(id: Int): MultiViewState = if (id in poppedIds) copy(poppedIds = poppedIds - id) else this

    fun popOutAll(): MultiViewState =
        if (poppedIds.size == tiles.size) this else copy(poppedIds = tiles.mapTo(HashSet()) { it.id })

    fun dockAll(): MultiViewState = if (poppedIds.isEmpty()) this else copy(poppedIds = emptySet())

    /** The state after leaving the grid: only the tiles in their own windows are left. */
    fun withoutDocked(): MultiViewState = docked.fold(this) { state, tile -> state.remove(tile.id) }

    fun setActive(id: Int): MultiViewState =
        if (tiles.any { it.id == id } && id != activeId) copy(activeId = id) else this

    /**
     * The state with tile [id] selected, for a tile whose sound is being adjusted: in
     * "selected only" mode what you adjust should be what you hear. Listening to all
     * keeps the selection where it is.
     */
    fun selecting(id: Int): MultiViewState = if (listenAll) this else setActive(id)

    /** True when tile [id] should make sound right now: selected (or listening to all) and not silent. */
    fun isAudible(id: Int): Boolean {
        val tile = tiles.firstOrNull { it.id == id } ?: return false
        return (listenAll || id == activeId) && !tile.silent
    }

    /** The state with tile [id] set to [volume]. Moving the volume of a muted tile brings its sound back. */
    fun withVolume(id: Int, volume: Int): MultiViewState =
        updateTile(id) { it.copy(volume = volume.coerceIn(0, 100), muted = false) }

    /**
     * The state with tile [id] muted, or unmuted when it was silent. Unmuting a tile
     * whose volume is 0 raises it to the default, so the button never does nothing.
     */
    fun toggleMute(id: Int): MultiViewState = updateTile(id) {
        when {
            !it.silent -> it.copy(muted = true)
            it.volume <= 0 -> it.copy(volume = DEFAULT_VOLUME, muted = false)
            else -> it.copy(muted = false)
        }
    }

    /** The state after Up or Down on the keyboard for tile [id], with the same rules as the stream screen. */
    fun nudge(id: Int, up: Boolean): MultiViewState = updateTile(id) {
        val (volume, off) = MultiShortcuts.nudged(it.volume, it.muted, up)
        it.copy(volume = volume, muted = off)
    }

    fun withListenAll(all: Boolean): MultiViewState = if (all == listenAll) this else copy(listenAll = all)

    private fun updateTile(id: Int, change: (MultiTile) -> MultiTile): MultiViewState {
        val i = tiles.indexOfFirst { it.id == id }
        if (i < 0) return this
        val changed = change(tiles[i])
        if (changed == tiles[i]) return this
        return copy(tiles = tiles.toMutableList().also { it[i] = changed })
    }

    /** The state with tiles [a] and [b] trading places on screen. */
    fun swap(a: Int, b: Int): MultiViewState {
        val i = tiles.indexOfFirst { it.id == a }
        val j = tiles.indexOfFirst { it.id == b }
        if (i < 0 || j < 0 || i == j) return this
        val moved = tiles.toMutableList()
        moved[i] = tiles[j]
        moved[j] = tiles[i]
        return copy(tiles = moved)
    }

    /**
     * The state with tile [id] moved [delta] places along the grid order (-1 is one
     * earlier). The tile it passes takes its old place. Nothing moves past either end.
     * Tiles in their own windows are not in the grid, so they are never passed.
     */
    fun move(id: Int, delta: Int): MultiViewState {
        val grid = docked
        val i = grid.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in grid.indices) return this
        return swap(id, grid[j].id)
    }

    /** The best quality any tile may play right now (see [TileQuality.capFor]). */
    fun qualityCap(preference: StreamQuality): StreamQuality = TileQuality.capFor(tiles.size, preference)

    companion object {
        const val MAX_TILES = 4
    }
}

/** The result of asking to add a channel (see [MultiViewState.addOutcome]). */
sealed interface AddOutcome {
    data object Added : AddOutcome
    data object Blank : AddOutcome
    data object Full : AddOutcome
    data class AlreadyShown(val tileId: Int) : AddOutcome
}

/** Where a tile sits in the grid: column and row, and how many columns it spans. */
data class GridCell(val column: Int, val row: Int, val columnSpan: Int = 1)

/** The grid for [count] tiles. */
data class GridShape(val columns: Int, val rows: Int)

object MultiViewLayout {
    fun shape(count: Int): GridShape = when {
        count <= 1 -> GridShape(1, 1)
        count == 2 -> GridShape(2, 1)
        else -> GridShape(2, 2)
    }

    /**
     * The cell of the tile at [index] of [count]. Three tiles are two on top and one
     * across the bottom, so no corner is left empty.
     */
    fun cell(index: Int, count: Int): GridCell = when {
        count <= 1 -> GridCell(0, 0)
        count == 2 -> GridCell(index, 0)
        count == 3 && index == 2 -> GridCell(0, 1, columnSpan = 2)
        else -> GridCell(index % 2, index / 2)
    }
}
