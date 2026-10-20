package com.puretv.twitch.desktop.ui

import com.puretv.twitch.core.model.StreamQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MultiViewStateTest {

    private fun stateOf(vararg logins: String): MultiViewState =
        logins.fold(MultiViewState()) { s, login -> s.add(login) }

    @Test fun theFirstTileBecomesActive() {
        val s = stateOf("shroud")
        assertEquals(listOf("shroud"), s.tiles.map { it.login })
        assertEquals(s.tiles.first().id, s.activeId)
    }

    @Test fun addingMoreKeepsTheActiveTile() {
        val s = stateOf("shroud", "xqc", "pokimane")
        assertEquals("shroud", s.active?.login)
        assertEquals(3, s.count)
    }

    @Test fun loginsAreCleanedAndDuplicatesIgnored() {
        val s = stateOf("Shroud", "  @XQC ", "shroud", "")
        assertEquals(listOf("shroud", "xqc"), s.tiles.map { it.login })
    }

    @Test fun stopsAtFourTiles() {
        val s = stateOf("a", "b", "c", "d", "e")
        assertEquals(4, s.count)
        assertEquals(false, s.canAdd)
        assertSame(s, s.add("f"))
    }

    @Test fun tileIdsAreNeverReused() {
        val s = stateOf("a", "b")
        val ids = s.tiles.map { it.id }
        val again = s.remove(ids[1]).add("c")
        assertEquals(false, again.tiles.last().id in ids)
    }

    @Test fun closingTheActiveTileMovesTheSoundToTheOneThatTookItsPlace() {
        val s = stateOf("a", "b", "c")
        val closed = s.remove(s.activeId!!)
        assertEquals("b", closed.active?.login)
    }

    @Test fun closingTheLastActiveTileMovesTheSoundToTheOneBefore() {
        val s = stateOf("a", "b", "c").let { it.setActive(it.tiles[2].id) }
        assertEquals("b", s.remove(s.activeId!!).active?.login)
    }

    @Test fun closingAnotherTileKeepsTheActiveOne() {
        val s = stateOf("a", "b", "c")
        val closed = s.remove(s.tiles[1].id)
        assertEquals("a", closed.active?.login)
        assertEquals(listOf("a", "c"), closed.tiles.map { it.login })
    }

    @Test fun closingEverythingLeavesNoActiveTile() {
        val s = stateOf("a")
        assertNull(s.remove(s.activeId!!).activeId)
    }

    @Test fun closingAnUnknownTileChangesNothing() {
        val s = stateOf("a", "b")
        assertSame(s, s.remove(999))
    }

    @Test fun makingATileActiveOnlyChangesTheActiveTile() {
        val s = stateOf("a", "b")
        val next = s.setActive(s.tiles[1].id)
        assertEquals("b", next.active?.login)
        assertEquals(s.tiles, next.tiles)
        assertSame(next, next.setActive(next.activeId!!))
        assertSame(s, s.setActive(999))
    }

    @Test fun swapTradesPlacesAndKeepsTheActiveTile() {
        val s = stateOf("a", "b", "c")
        val swapped = s.swap(s.tiles[0].id, s.tiles[2].id)
        assertEquals(listOf("c", "b", "a"), swapped.tiles.map { it.login })
        assertEquals("a", swapped.active?.login)
        assertSame(s, s.swap(s.tiles[0].id, s.tiles[0].id))
        assertSame(s, s.swap(s.tiles[0].id, 999))
    }

    @Test fun qualityCapFollowsTheTileCount() {
        assertEquals(StreamQuality.P1080P60, stateOf("a", "b").qualityCap(StreamQuality.AUTO))
        assertEquals(StreamQuality.P720P60, stateOf("a", "b", "c").qualityCap(StreamQuality.AUTO))
        assertEquals(StreamQuality.P720P60, stateOf("a", "b", "c", "d").qualityCap(StreamQuality.SOURCE))
        assertEquals(StreamQuality.P480P, stateOf("a", "b", "c").qualityCap(StreamQuality.P480P))
    }

    @Test fun qualityCapIgnoresWhichTileIsActive() {
        val s = stateOf("a", "b", "c")
        val other = s.setActive(s.tiles[2].id)
        assertEquals(s.qualityCap(StreamQuality.AUTO), other.qualityCap(StreamQuality.AUTO))
    }

    @Test fun twoTilesSitSideBySide() {
        assertEquals(GridShape(2, 1), MultiViewLayout.shape(2))
        assertEquals(GridCell(0, 0), MultiViewLayout.cell(0, 2))
        assertEquals(GridCell(1, 0), MultiViewLayout.cell(1, 2))
    }

    @Test fun threeTilesAreTwoOnTopAndOneAcrossTheBottom() {
        assertEquals(GridShape(2, 2), MultiViewLayout.shape(3))
        assertEquals(GridCell(0, 0), MultiViewLayout.cell(0, 3))
        assertEquals(GridCell(1, 0), MultiViewLayout.cell(1, 3))
        assertEquals(GridCell(0, 1, columnSpan = 2), MultiViewLayout.cell(2, 3))
    }

    @Test fun fourTilesFillATwoByTwoGrid() {
        assertEquals(GridShape(2, 2), MultiViewLayout.shape(4))
        assertEquals(
            listOf(GridCell(0, 0), GridCell(1, 0), GridCell(0, 1), GridCell(1, 1)),
            (0 until 4).map { MultiViewLayout.cell(it, 4) },
        )
    }

    @Test fun aSingleTileFillsTheScreen() {
        assertEquals(GridShape(1, 1), MultiViewLayout.shape(1))
        assertEquals(GridCell(0, 0), MultiViewLayout.cell(0, 1))
    }

    @Test fun moveShiftsATileOnePlaceAndKeepsIdsAndTheActiveTile() {
        val s = stateOf("a", "b", "c")
        val a = s.tiles[0].id
        val later = s.move(a, 1)
        assertEquals(listOf("b", "a", "c"), later.tiles.map { it.login })
        assertEquals(s.activeId, later.activeId)
        assertEquals(s.tiles.map { it.id }.toSet(), later.tiles.map { it.id }.toSet())
        assertEquals(listOf("a", "b", "c"), later.move(a, -1).tiles.map { it.login })
    }

    @Test fun moveStopsAtBothEnds() {
        val s = stateOf("a", "b")
        assertSame(s, s.move(s.tiles.first().id, -1))
        assertSame(s, s.move(s.tiles.last().id, 1))
        assertSame(s, s.move(99, 1))
    }

    @Test fun gridPositionsFollowTheOrderNotTheTileId() {
        // Cells are looked up by position, so a moved tile lands in its new cell while
        // the screen keeps each tile keyed by id (no surface is recreated).
        val s = stateOf("a", "b", "c").move(1, 2)
        assertEquals(listOf("c", "b", "a"), s.tiles.map { it.login })
        assertEquals(GridCell(0, 1, columnSpan = 2), MultiViewLayout.cell(s.tiles.indexOfFirst { it.login == "a" }, s.count))
    }
}
