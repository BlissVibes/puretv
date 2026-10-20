package com.puretv.twitch.desktop.ui

import com.puretv.twitch.core.model.StreamQuality
import com.puretv.twitch.desktop.player.TileQuality

/** One stream on the multi-stream screen. [id] stays with the tile for as long as it is open. */
data class MultiTile(val id: Int, val login: String)

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
) {
    val count: Int get() = tiles.size
    val canAdd: Boolean get() = tiles.size < MAX_TILES
    val active: MultiTile? get() = tiles.firstOrNull { it.id == activeId }

    fun contains(login: String): Boolean = tiles.any { it.login.equals(login, ignoreCase = true) }

    /**
     * The state with [login] added as the last tile. The first tile becomes active;
     * after that the active tile stays, so adding a channel never moves the sound.
     * A channel already on screen, a blank name, or a full screen change nothing.
     */
    fun add(login: String): MultiViewState {
        val clean = login.trim().removePrefix("@").lowercase()
        if (clean.isEmpty() || contains(clean) || !canAdd) return this
        val tile = MultiTile(nextId, clean)
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
        return copy(tiles = rest, activeId = nextActive)
    }

    fun setActive(id: Int): MultiViewState =
        if (tiles.any { it.id == id } && id != activeId) copy(activeId = id) else this

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
     */
    fun move(id: Int, delta: Int): MultiViewState {
        val i = tiles.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in tiles.indices) return this
        return swap(id, tiles[j].id)
    }

    /** The best quality any tile may play right now (see [TileQuality.capFor]). */
    fun qualityCap(preference: StreamQuality): StreamQuality = TileQuality.capFor(tiles.size, preference)

    companion object {
        const val MAX_TILES = 4
    }
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
