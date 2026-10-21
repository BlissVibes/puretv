package com.puretv.twitch.desktop.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.event.KeyEvent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PopoutLayoutTest {
    // The owner's desk: primary 2560x1440, one monitor to the left (negative x), one above (negative y).
    private val primary = MiniRect(0, 0, 2560, 1400)
    private val left = MiniRect(-1920, 0, 1920, 1040)
    private val above = MiniRect(0, -1080, 1920, 1040)
    private val desk = listOf(primary, left, above)

    private fun assertWithin(inner: MiniRect, outer: MiniRect) {
        assertTrue(inner.x >= outer.x && inner.y >= outer.y, "$inner starts outside $outer")
        assertTrue(inner.x + inner.width <= outer.x + outer.width && inner.y + inner.height <= outer.y + outer.height, "$inner ends outside $outer")
    }

    @Test fun fitInKeepsSixteenByNineAndCentres() {
        val fit = PopoutLayout.fitIn(MiniRect(100, 200, 2000, 1000))
        assertEquals(PopoutLayout.heightFor(fit.width), fit.height)
        assertWithin(fit, MiniRect(100, 200, 2000, 1000))
        assertEquals(100 + (2000 - fit.width) / 2, fit.x)
    }

    @Test fun monitorsFillLeftToRightWithTheMainOneLast() {
        assertEquals(listOf(1, 0, 2), PopoutLayout.monitorOrder(desk, avoid = null).let { it.filter { i -> i != 2 } + 2 })
        val order = PopoutLayout.monitorOrder(desk, avoid = 0)
        assertEquals(0, order.last())
        assertEquals(setOf(1, 2), order.dropLast(1).toSet())
    }

    @Test fun oneWindowPerTileEachFullyOnAMonitorAcrossThreeMonitors() {
        for (n in 1..4) {
            val places = PopoutLayout.assign(n, desk, avoid = 0)
            assertEquals(n, places.size)
            places.forEach { p ->
                assertTrue(desk.any { work -> p.intersect(work) == p }, "$p is not inside one monitor")
                assertTrue(MiniPlayerGeometry.isReachable(p, desk))
                assertTrue(kotlin.math.abs(p.height - PopoutLayout.heightFor(p.width)) <= 1, "$p is not 16:9 plus strip")
            }
        }
    }

    @Test fun threeTilesGoToThreeDifferentMonitorsAndTheMainOneIsLast() {
        val places = PopoutLayout.assign(3, desk, avoid = 0)
        val monitors = places.map { PopoutLayout.monitorOf(it, desk) }
        assertEquals(3, monitors.toSet().size)
        assertEquals(0, monitors.last())
    }

    @Test fun moreTilesThanMonitorsShareAndNeverOverlap() {
        val places = PopoutLayout.assign(4, desk, avoid = 0)
        val shared = places.groupBy { PopoutLayout.monitorOf(it, desk) }.values.first { it.size > 1 }
        for (a in shared.indices) for (b in a + 1 until shared.size) assertNull(shared[a].intersect(shared[b]))
    }

    @Test fun oneMonitorTilesTheWindows() {
        val places = PopoutLayout.assign(4, listOf(primary))
        assertEquals(4, places.size)
        places.forEach { assertWithin(it, primary) }
        for (a in places.indices) for (b in a + 1 until places.size) assertNull(places[a].intersect(places[b]))
        assertEquals(2, PopoutLayout.assign(2, listOf(primary)).map { it.x }.toSet().size)
    }

    @Test fun noMonitorsOrTilesGivesNothing() {
        assertTrue(PopoutLayout.assign(2, emptyList()).isEmpty())
        assertTrue(PopoutLayout.assign(0, desk).isEmpty())
        assertNull(PopoutLayout.placeOne(emptyList(), null, 0))
    }

    @Test fun aSinglePopOutPrefersAnotherMonitorAndCascades() {
        val first = PopoutLayout.placeOne(desk, avoid = 0, openCount = 0)!!
        assertNotNull(PopoutLayout.monitorOf(first, desk))
        assertTrue(PopoutLayout.monitorOf(first, desk) != 0)
        val second = PopoutLayout.placeOne(desk, avoid = 0, openCount = 1)!!
        assertTrue(second.x != first.x)
        listOf(first, second).forEach { assertWithin(it, desk[PopoutLayout.monitorOf(it, desk)!!]) }
        val alone = PopoutLayout.placeOne(listOf(primary), avoid = 0, openCount = 0)!!
        assertWithin(alone, primary)
    }

    @Test fun aRememberedSpotIsKeptWhenReachableAndRescuedWhenItsMonitorIsGone() {
        val fallback = PopoutLayout.placeOne(listOf(primary), null, 0)!!
        val onLeft = MiniRect(-1500, 100, 960, 588)
        assertEquals(onLeft, PopoutLayout.resolve(onLeft, fallback, desk))
        val rescued = PopoutLayout.resolve(onLeft, fallback, listOf(primary))
        assertTrue(MiniPlayerGeometry.isReachable(rescued, listOf(primary)))
        assertSame(fallback, PopoutLayout.resolve(null, fallback, desk))
    }

    @Test fun theBoundsStoreRoundTripsAndIgnoresGarbage() {
        val file = File.createTempFile("popout", ".txt")
        try {
            val store = PopoutBoundsStore(file)
            store.remember("Shroud", MiniRect(-1500, 20, 960, 588))
            store.flush()
            assertEquals(MiniRect(-1500, 20, 960, 588), PopoutBoundsStore(file).recall("shroud"))
            file.writeText("bad line\nx 1 2 3\nabsurd 999999999 0 10 10\nok 1 2 300 200")
            val read = PopoutBoundsStore(file)
            assertNull(read.recall("absurd"))
            assertEquals(MiniRect(1, 2, 300, 200), read.recall("ok"))
        } finally {
            file.delete()
        }
    }
}

class PopoutStateTest {
    private fun stateOf(vararg logins: String) = logins.fold(MultiViewState()) { s, l -> s.add(l) }

    @Test fun poppedTilesLeaveTheGridButStayTiles() {
        val s = stateOf("a", "b", "c")
        val b = s.tiles[1].id
        val popped = s.popOut(b)
        assertEquals(listOf("a", "c"), popped.docked.map { it.login })
        assertEquals(listOf("b"), popped.popped.map { it.login })
        assertEquals(3, popped.count)
        assertTrue(popped.isPopped(b))
    }

    @Test fun dockingBringsItBackInPlaceAndOddCallsChangeNothing() {
        val s = stateOf("a", "b")
        val a = s.tiles[0].id
        assertSame(s, s.dock(a))
        assertSame(s, s.popOut(999))
        val back = s.popOut(a).dock(a)
        assertEquals(listOf("a", "b"), back.docked.map { it.login })
        assertTrue(back.popped.isEmpty())
    }

    @Test fun popOutAllAndDockAll() {
        val s = stateOf("a", "b", "c", "d")
        val all = s.popOutAll()
        assertTrue(all.docked.isEmpty())
        assertEquals(4, all.popped.size)
        assertSame(all, all.popOutAll())
        assertTrue(all.dockAll().popped.isEmpty())
        assertSame(s, s.dockAll())
    }

    @Test fun closingAPoppedTileForgetsItAndMovesTheSound() {
        val s = stateOf("a", "b").let { it.popOut(it.tiles[0].id) }
        val a = s.tiles[0].id
        assertEquals(a, s.activeId)
        val closed = s.remove(a)
        assertFalse(closed.isPopped(a))
        assertEquals(listOf("b"), closed.tiles.map { it.login })
        assertEquals(closed.tiles[0].id, closed.activeId)
    }

    @Test fun leavingTheGridKeepsOnlyThePoppedTiles() {
        val s = stateOf("a", "b", "c").let { it.popOut(it.tiles[1].id) }
        val left = s.withoutDocked()
        assertEquals(listOf("b"), left.tiles.map { it.login })
        assertEquals(left.tiles[0].id, left.activeId)
        assertTrue(stateOf("a").withoutDocked().tiles.isEmpty())
    }

    @Test fun movingSkipsPoppedTilesAndSoundIsSharedAcrossWindows() {
        val s = stateOf("a", "b", "c").let { it.popOut(it.tiles[1].id) }
        val a = s.tiles[0].id
        val c = s.tiles[2].id
        assertEquals(listOf("c", "a"), s.move(a, 1).docked.map { it.login })
        // Selecting a popped tile (a click in its window) moves the sound; in "selected only" it is the only one heard.
        val b = s.tiles[1].id
        val picked = s.selecting(b)
        assertTrue(picked.isAudible(b))
        assertFalse(picked.isAudible(c))
        assertEquals(TileSource.Live("b"), picked.tiles[1].source)
    }
}

class MultiStreamSessionTest {
    private class FakeHost(initial: String) : MultiTileHost {
        var layout = MultiViewState().add(initial)
        override val state: StateFlow<MultiViewUiState> get() = MutableStateFlow(MultiViewUiState(layout = layout))
        var shutDown = false
        override fun addChannel(login: String): Boolean {
            val after = layout.add(login)
            return (after !== layout).also { layout = after }
        }
        override fun addVod(source: TileSource.Vod): Boolean {
            val after = layout.addVod(source)
            return (after !== layout).also { layout = after }
        }
        override fun closeTile(id: Int): List<MultiTile> { layout = layout.remove(id); return layout.tiles }
        override fun closeDocked(): List<MultiTile> { layout = layout.withoutDocked(); return layout.tiles }
        override fun popOut(id: Int) { layout = layout.popOut(id) }
        override fun dock(id: Int) { layout = layout.dock(id) }
        override fun popOutAll() { layout = layout.popOutAll() }
        override fun dockAll() { layout = layout.dockAll() }
        override fun shutDown() { shutDown = true }
    }

    private fun session(): MultiStreamSession<FakeHost> =
        MultiStreamSession(create = { FakeHost(it) }, boundsStore = PopoutBoundsStore(File.createTempFile("popout-bounds", ".txt").also { it.delete() }))

    @Test fun openingTwiceKeepsOneSessionAndAddsTheChannel() {
        val s = session()
        val first = s.open("a")
        assertSame(first, s.open("b"))
        assertEquals(listOf("a", "b"), first.layout.tiles.map { it.login })
    }

    @Test fun leavingTheGridStopsDockedTilesButKeepsPoppedOnes() {
        val s = session()
        val host = s.open("a")
        s.open("b")
        s.popOut(host.layout.tiles[1].id)
        s.leaveGrid()
        assertSame(host, s.current.value)
        assertEquals(listOf("b"), host.layout.tiles.map { it.login })
        assertFalse(host.shutDown)
    }

    @Test fun leavingTheGridWithNothingPoppedEndsTheSession() {
        val s = session()
        val host = s.open("a")
        s.leaveGrid()
        assertNull(s.current.value)
        assertTrue(host.shutDown)
    }

    @Test fun theSessionEndsWhenTheLastTileCloses() {
        val s = session()
        val host = s.open("a")
        s.open("b")
        s.popOutAll()
        val ids = host.layout.tiles.map { it.id }
        assertEquals(1, s.closeTile(ids[0]).size)
        assertNotNull(s.current.value)
        assertTrue(s.closeTile(ids[1]).isEmpty())
        assertNull(s.current.value)
        assertTrue(host.shutDown)
    }

    @Test fun endStopsEverythingAndANewOpenStartsFresh() {
        val s = session()
        val host = s.open("a")
        s.popOutAll()
        s.end()
        assertTrue(host.shutDown)
        assertNull(s.current.value)
        val fresh = s.open("c")
        assertTrue(fresh !== host)
        assertEquals(listOf("c"), fresh.layout.tiles.map { it.login })
    }
}

class PopoutShortcutsTest {
    private fun action(code: Int, ctrl: Boolean = false, fullscreen: Boolean = false, typing: Boolean = false, repeat: Boolean = false) =
        PopoutShortcuts.actionFor(code, ctrl, alt = false, fullscreen = fullscreen, textInputFocused = typing, isRepeat = repeat)

    @Test fun theKeysMapToTheirActions() {
        assertEquals(PopoutShortcutAction.VolumeUp, action(KeyEvent.VK_UP))
        assertEquals(PopoutShortcutAction.VolumeDown, action(KeyEvent.VK_DOWN))
        assertEquals(PopoutShortcutAction.ToggleMute, action(KeyEvent.VK_M))
        assertEquals(PopoutShortcutAction.ToggleFullscreen, action(KeyEvent.VK_F))
        assertEquals(PopoutShortcutAction.Dock, action(KeyEvent.VK_D))
        assertEquals(PopoutShortcutAction.CloseTile, action(KeyEvent.VK_W, ctrl = true))
    }

    @Test fun escapeOnlyLeavesFullscreenAndNeverCloses() {
        assertNull(action(KeyEvent.VK_ESCAPE))
        assertEquals(PopoutShortcutAction.ExitFullscreen, action(KeyEvent.VK_ESCAPE, fullscreen = true))
        assertNull(action(KeyEvent.VK_W))
    }

    @Test fun typingAndAltStandDownAndOnlyVolumeRepeats() {
        assertNull(action(KeyEvent.VK_M, typing = true))
        assertNull(PopoutShortcuts.actionFor(KeyEvent.VK_M, false, alt = true, fullscreen = false))
        assertNull(action(KeyEvent.VK_M, repeat = true))
        assertEquals(PopoutShortcutAction.VolumeUp, action(KeyEvent.VK_UP, repeat = true))
    }
}

class PopoutFollowUpTest {
    private val primary = MiniRect(0, 0, 2560, 1400)
    private val left = MiniRect(-1920, 0, 1920, 1040)
    private val above = MiniRect(0, -1080, 1920, 1040)
    private val desk = listOf(primary, left, above)

    @Test fun monitorsHoldingAPoppedWindowAreFilledAfterTheFreeOnes() {
        val windowOnLeft = MiniRect(-1500, 100, 960, 588)
        val order = PopoutLayout.monitorOrder(desk, avoid = 0, occupied = listOf(windowOnLeft))
        assertEquals(listOf(2, 1, 0), order)
        val places = PopoutLayout.assign(1, desk, avoid = 0, occupied = listOf(windowOnLeft))
        assertEquals(2, PopoutLayout.monitorOf(places.single(), desk))
    }

    @Test fun savingIsOffWhileFullscreenOrMaximizedAndTheFlagIsImmediate() {
        val gate = PopoutSaveGate()
        assertTrue(gate.shouldSave(maximized = false))
        assertFalse(gate.shouldSave(maximized = true))
        gate.fullscreen = true
        assertFalse(gate.shouldSave(maximized = false))
        gate.fullscreen = false
        assertTrue(gate.shouldSave(maximized = false))
    }

    @Test fun aHandlerThatStoodDownDoesNotSwallowTheNextPress() {
        val held = HeldKey()
        assertFalse(held.pressed(KeyEvent.VK_M))
        assertTrue(held.pressed(KeyEvent.VK_M))
        // The key was released while another window had the keyboard, so no release was seen.
        held.clear()
        assertFalse(held.pressed(KeyEvent.VK_M))
        held.released(KeyEvent.VK_M)
        assertFalse(held.pressed(KeyEvent.VK_M))
        assertFalse(held.pressed(KeyEvent.VK_UP))
    }

    @Test fun aRefusedAddSaysWhy() {
        val s = MultiViewState().add("a").add("b")
        assertEquals(AddOutcome.Added, s.addOutcome("c"))
        assertEquals(AddOutcome.Blank, s.addOutcome("  "))
        assertEquals(AddOutcome.AlreadyShown(s.tiles[1].id), s.addOutcome("@B"))
        assertEquals(AddOutcome.Full, s.add("c").add("d").addOutcome("e"))
    }
}
