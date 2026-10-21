package com.puretv.twitch.desktop.ui

import com.puretv.twitch.core.model.PlaylistVariant
import com.puretv.twitch.core.model.StreamQuality
import com.puretv.twitch.core.vod.WatchProgress
import com.puretv.twitch.desktop.player.TileQuality
import com.puretv.twitch.desktop.player.VodPlayback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.event.KeyEvent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun vod(id: String, channel: String = "shroud", start: Long = 0) =
    TileSource.Vod(id, "Title $id", channel, start, VodKind.PAST_BROADCAST)

class VodTileStateTest {
    @Test fun aVodTileIsATileOfItsChannelWithAVodSource() {
        val s = MultiViewState().addVod(vod("111", "Shroud"))
        val tile = s.tiles.single()
        assertEquals("shroud", tile.login)
        assertTrue(tile.isVod)
        assertNull(tile.liveLogin)
        assertEquals(tile.id, s.activeId)
        assertEquals("video-111", tile.placeKey)
    }

    @Test fun aLiveTileAndAVodOfTheSameChannelCanBothBeShown() {
        val s = MultiViewState().add("shroud").addVod(vod("111"))
        assertEquals(2, s.count)
        assertTrue(s.contains("shroud"))
        // The live check ignores the video, and a second live tile of the channel is still refused.
        assertSame(s, s.add("shroud"))
        val onlyVod = MultiViewState().addVod(vod("111"))
        assertFalse(onlyVod.contains("shroud"))
        assertEquals(AddOutcome.Added, onlyVod.addOutcome("shroud"))
    }

    @Test fun theSameVideoIsNotAddedTwiceButAnotherOfTheChannelIs() {
        val s = MultiViewState().addVod(vod("111"))
        val id = s.tiles[0].id
        assertEquals(AddOutcome.AlreadyShown(id), s.addVodOutcome(" 111 "))
        assertSame(s, s.addVod(vod("111")))
        assertEquals(2, s.addVod(vod("222")).count)
        assertEquals(AddOutcome.Blank, s.addVodOutcome("  "))
    }

    @Test fun fourTilesIsTheLimitForAMixOfLiveAndVideos() {
        val s = MultiViewState().add("a").addVod(vod("1")).add("b").addVod(vod("2"))
        assertEquals(4, s.count)
        assertEquals(AddOutcome.Full, s.addVodOutcome("3"))
        assertEquals(AddOutcome.Full, s.addOutcome("c"))
        assertSame(s, s.addVod(vod("3")))
        assertSame(s, s.add("c"))
    }

    @Test fun startPositionIsNeverNegativeAndTheNewTileIsNeverSilent() {
        val s = MultiViewState().addVod(vod("1", start = -5), volume = 0)
        assertEquals(0, s.tiles[0].vod!!.startPositionMs)
        assertEquals(1, s.tiles[0].volume)
    }

    @Test fun removeMoveAndPopOutWorkLikeForLiveTiles() {
        val s = MultiViewState().add("a").addVod(vod("1")).addVod(vod("2"))
        val v1 = s.tiles[1].id
        val v2 = s.tiles[2].id
        assertEquals(listOf(v1, v2), s.popOutAll().popped.map { it.id }.filter { it != s.tiles[0].id })
        val popped = s.popOut(v1)
        assertTrue(popped.isPopped(v1))
        assertEquals(listOf("a", "shroud"), popped.docked.map { it.login })
        assertEquals(listOf(v2, s.tiles[0].id), popped.move(v2, -1).docked.map { it.id })
        assertFalse(popped.dock(v1).isPopped(v1))
        val closed = s.remove(v1)
        assertFalse(closed.containsVod("1"))
        assertTrue(closed.containsVod("2"))
        // The sound moves on from a closed active video like from any tile.
        val active = MultiViewState().addVod(vod("1")).add("b")
        assertEquals("b", active.remove(active.activeId!!).active?.login)
    }

    @Test fun volumeMuteAndSelectionBehaveTheSameForVideos() {
        val s = MultiViewState().add("a").addVod(vod("1"))
        val v = s.tiles[1].id
        assertFalse(s.isAudible(v))
        assertTrue(s.withListenAll(true).isAudible(v))
        val picked = s.selecting(v)
        assertTrue(picked.isAudible(v))
        assertTrue(picked.toggleMute(v).tiles[1].silent)
        assertEquals(40, picked.withVolume(v, 40).tiles[1].volume)
    }

    @Test fun theVideoKindFollowsTheTwitchVideoType() {
        assertEquals(VodKind.HIGHLIGHT, VodKind.from(com.puretv.twitch.core.model.VideoType.HIGHLIGHT))
        assertEquals(VodKind.UPLOAD, VodKind.from(com.puretv.twitch.core.model.VideoType.UPLOAD))
        assertEquals(VodKind.PAST_BROADCAST, VodKind.from(com.puretv.twitch.core.model.VideoType.ARCHIVE))
    }
}

class VodTileLogicTest {
    @Test fun parsesLinksIdsAndStartTimes() {
        assertEquals(ParsedVideo("2902385151"), VodTileLogic.parseVideo("https://www.twitch.tv/videos/2902385151"))
        assertEquals(ParsedVideo("123"), VodTileLogic.parseVideo("twitch.tv/videos/123"))
        assertEquals(ParsedVideo("123", (3600 + 2 * 60 + 3) * 1000L), VodTileLogic.parseVideo("https://www.twitch.tv/videos/123?t=1h2m3s"))
        assertEquals(ParsedVideo("123", 90_000), VodTileLogic.parseVideo("https://m.twitch.tv/videos/123?filter=archives&t=1m30s"))
        assertEquals(ParsedVideo("123"), VodTileLogic.parseVideo("https://www.twitch.tv/shroud/video/123"))
        assertEquals(ParsedVideo("456"), VodTileLogic.parseVideo(" v456 "))
        assertEquals(ParsedVideo("789"), VodTileLogic.parseVideo("789"))
    }

    @Test fun refusesWhatIsNotAVideo() {
        assertNull(VodTileLogic.parseVideo(""))
        assertNull(VodTileLogic.parseVideo("shroud"))
        assertNull(VodTileLogic.parseVideo("https://www.twitch.tv/shroud"))
        assertNull(VodTileLogic.parseVideo("https://example.com/videos/123"))
        assertNull(VodTileLogic.parseVideo("12345678901234567890"))
    }

    private fun saved(position: Long, duration: Long = 3_600_000) = WatchProgress("1", position, duration, 0)

    @Test fun startsWhereAsked_elseResumesSilently_elseAtZero() {
        assertEquals(90_000, VodTileLogic.initialStartMs(90_000, saved(500_000)))
        assertEquals(500_000, VodTileLogic.initialStartMs(0, saved(500_000)))
        // Too early to matter, finished, or nothing saved: from the beginning.
        assertEquals(0, VodTileLogic.initialStartMs(0, saved(10_000)))
        assertEquals(0, VodTileLogic.initialStartMs(0, saved(3_500_000)))
        assertEquals(0, VodTileLogic.initialStartMs(0, null))
    }

    @Test fun aRetryNeverGoesBackToTheBeginning() {
        assertEquals(754_000, VodTileLogic.retryStartMs(754_000, 0, 3_600_000))
        assertEquals(754_000, VodTileLogic.retryStartMs(754_000, 90_000, 0))
        // Only a tile that never played falls back to where it was asked to start.
        assertEquals(90_000, VodTileLogic.retryStartMs(0, 90_000, 3_600_000))
        assertEquals(0, VodTileLogic.retryStartMs(0, 0, 3_600_000))
        // A reading past the end is held at the end.
        assertEquals(3_600_000, VodTileLogic.retryStartMs(9_999_999, 0, 3_600_000))
    }

    @Test fun seeksStayInsideTheVideo() {
        assertEquals(0, VodTileLogic.clampSeek(-5, 1000))
        assertEquals(1000, VodTileLogic.clampSeek(5000, 1000))
        assertEquals(400, VodTileLogic.clampSeek(400, 1000))
        assertEquals(400, VodTileLogic.clampSeek(400, 0))
        assertEquals(0, VodTileLogic.clampSeek(-400, 0))
        assertEquals(35_000, VodTileLogic.seekTarget(30_000, 5_000, 600_000, true))
        assertEquals(0, VodTileLogic.seekTarget(3_000, -30_000, 600_000, true))
        assertEquals(600_000, VodTileLogic.seekTarget(595_000, 30_000, 600_000, true))
        // Not seekable yet, or no length yet: the key is left alone.
        assertNull(VodTileLogic.seekTarget(0, 5_000, 600_000, false))
        assertNull(VodTileLogic.seekTarget(0, 5_000, 0, true))
    }

    @Test fun theSliderMapsToPositionsAndBack() {
        assertEquals(0f, VodTileLogic.fraction(10, 0))
        assertEquals(0.5f, VodTileLogic.fraction(500, 1000))
        assertEquals(1f, VodTileLogic.fraction(5000, 1000))
        assertEquals(250, VodTileLogic.positionAt(0.25f, 1000))
        assertEquals(1000, VodTileLogic.positionAt(2f, 1000))
    }

    @Test fun progressIsSavedOnlyOnceItIsWorthResuming() {
        assertFalse(VodTileLogic.shouldSave(0, 600_000))
        assertFalse(VodTileLogic.shouldSave(19_999, 600_000))
        assertTrue(VodTileLogic.shouldSave(20_000, 600_000))
        assertFalse(VodTileLogic.shouldSave(60_000, 0))
    }

    @Test fun startTimeOptionsAndReplayPosition() {
        assertContentEquals(emptyArray<String>(), VodPlayback.startOptions(0))
        assertContentEquals(arrayOf(":start-time=300.000"), VodPlayback.startOptions(300_000))
        assertContentEquals(arrayOf(":start-time=12.050"), VodPlayback.startOptions(12_050))
        // Played again after it ended: from the start. Otherwise from where it was.
        assertEquals(0, VodPlayback.replayStartMs(599_000, 600_000))
        assertEquals(120_000, VodPlayback.replayStartMs(120_000, 600_000))
        assertEquals(120_000, VodPlayback.replayStartMs(120_000, 0))
    }
}

class VodRenditionCapTest {
    private fun v(res: String, fps: Double, url: String) = PlaylistVariant(StreamQuality.AUTO, res, fps, 0, url)

    private val ladder = listOf(
        v("1920x1080", 60.0, "1080p60"),
        v("1280x720", 60.0, "720p60"),
        v("1280x720", 30.0, "720p30"),
        v("852x480", 30.0, "480p"),
        v("640x360", 30.0, "360p"),
        v("", 0.0, "audio_only"),
    )

    @Test fun picksTheBestRenditionAtOrBelowTheCap() {
        assertEquals("1080p60", TileQuality.vodUrl("master", ladder, 1080))
        assertEquals("720p60", TileQuality.vodUrl("master", ladder, 720))
        assertEquals("480p", TileQuality.vodUrl("master", ladder, 500))
        assertEquals("360p", TileQuality.vodUrl("master", ladder, 360))
    }

    @Test fun theCapFollowsTheTileCount() {
        val twoTiles = TileQuality.maxHeight(TileQuality.capFor(2, StreamQuality.AUTO))
        val fourTiles = TileQuality.maxHeight(TileQuality.capFor(4, StreamQuality.AUTO))
        assertEquals("1080p60", TileQuality.vodUrl("master", ladder, twoTiles))
        assertEquals("720p60", TileQuality.vodUrl("master", ladder, fourTiles))
    }

    @Test fun belowEveryRenditionKeepsTheSmallestAndNoLadderPlaysTheMaster() {
        assertEquals("360p", TileQuality.vodUrl("master", ladder, 144))
        assertEquals("master", TileQuality.vodUrl("master", emptyList(), 720))
        assertEquals("master", TileQuality.vodUrl("master", listOf(v("", 0.0, "audio_only")), 720))
    }
}

class VodTileShortcutsTest {
    private fun multi(code: Int, vod: Boolean, shift: Boolean = false, typing: Boolean = false, repeat: Boolean = false, ctrl: Boolean = false) =
        MultiShortcuts.actionFor(code, ctrl, alt = false, textInputFocused = typing, isRepeat = repeat, shift = shift, vodSelected = vod)

    private fun popped(code: Int, vod: Boolean, shift: Boolean = false, typing: Boolean = false, repeat: Boolean = false) =
        PopoutShortcuts.actionFor(code, ctrl = false, alt = false, fullscreen = false, textInputFocused = typing, isRepeat = repeat, shift = shift, vod = vod)

    @Test fun theTransportKeysMapForASelectedVideo() {
        assertEquals(MultiShortcutAction.TogglePlayPause, multi(KeyEvent.VK_SPACE, vod = true))
        assertEquals(MultiShortcutAction.SeekBack, multi(KeyEvent.VK_LEFT, vod = true))
        assertEquals(MultiShortcutAction.SeekForward, multi(KeyEvent.VK_RIGHT, vod = true))
        assertEquals(MultiShortcutAction.SeekBackLarge, multi(KeyEvent.VK_LEFT, vod = true, shift = true))
        assertEquals(MultiShortcutAction.SeekForwardLarge, multi(KeyEvent.VK_RIGHT, vod = true, shift = true))
        assertEquals(MultiShortcutAction.JumpBack, multi(KeyEvent.VK_J, vod = true))
        assertEquals(MultiShortcutAction.JumpForward, multi(KeyEvent.VK_L, vod = true))
    }

    @Test fun theSkipDistancesAreFiveTenAndThirtySeconds() {
        assertEquals(-5_000L, MultiShortcutAction.SeekBack.seekDeltaMs())
        assertEquals(5_000L, MultiShortcutAction.SeekForward.seekDeltaMs())
        assertEquals(-30_000L, MultiShortcutAction.SeekBackLarge.seekDeltaMs())
        assertEquals(30_000L, MultiShortcutAction.SeekForwardLarge.seekDeltaMs())
        assertEquals(-10_000L, MultiShortcutAction.JumpBack.seekDeltaMs())
        assertEquals(10_000L, MultiShortcutAction.JumpForward.seekDeltaMs())
        assertNull(MultiShortcutAction.ToggleMute.seekDeltaMs())
        assertEquals(10_000L, PopoutShortcutAction.JumpForward.seekDeltaMs())
        assertEquals(-30_000L, PopoutShortcutAction.SeekBackLarge.seekDeltaMs())
    }

    @Test fun aLiveSelectionLeavesTheseKeysAlone() {
        for (code in listOf(KeyEvent.VK_SPACE, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_J, KeyEvent.VK_L)) {
            assertNull(multi(code, vod = false), "multi $code")
            assertNull(popped(code, vod = false), "popped $code")
        }
    }

    @Test fun volumeMuteAndMovingAreUnchangedForVideos() {
        assertEquals(MultiShortcutAction.VolumeUp, multi(KeyEvent.VK_UP, vod = true))
        assertEquals(MultiShortcutAction.ToggleMute, multi(KeyEvent.VK_M, vod = true))
        assertEquals(MultiShortcutAction.MoveEarlier, multi(KeyEvent.VK_LEFT, vod = true, ctrl = true))
    }

    @Test fun typingStandsDownAndOnlyVolumeAndSkipsRepeat() {
        assertNull(multi(KeyEvent.VK_SPACE, vod = true, typing = true))
        assertNull(multi(KeyEvent.VK_LEFT, vod = true, typing = true))
        assertNull(multi(KeyEvent.VK_SPACE, vod = true, repeat = true))
        assertNull(multi(KeyEvent.VK_M, vod = true, repeat = true))
        assertEquals(MultiShortcutAction.SeekForward, multi(KeyEvent.VK_RIGHT, vod = true, repeat = true))
        assertNull(popped(KeyEvent.VK_SPACE, vod = true, repeat = true))
        assertEquals(PopoutShortcutAction.JumpBack, popped(KeyEvent.VK_J, vod = true, repeat = true))
        assertNull(popped(KeyEvent.VK_SPACE, vod = true, typing = true))
    }

    @Test fun theWindowOfAVideoHasTheSameTransportKeys() {
        assertEquals(PopoutShortcutAction.TogglePlayPause, popped(KeyEvent.VK_SPACE, vod = true))
        assertEquals(PopoutShortcutAction.SeekForwardLarge, popped(KeyEvent.VK_RIGHT, vod = true, shift = true))
        assertEquals(PopoutShortcutAction.ToggleFullscreen, popped(KeyEvent.VK_F, vod = true))
    }
}

class VodSessionTest {
    private class FakeHost(initial: TileSource) : MultiTileHost {
        var layout = when (initial) {
            is TileSource.Live -> MultiViewState().add(initial.login)
            is TileSource.Vod -> MultiViewState().addVod(initial)
        }
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

    private fun session() = MultiStreamSession(
        create = { FakeHost(TileSource.Live(it)) },
        boundsStore = PopoutBoundsStore(File.createTempFile("popout-bounds", ".txt").also { it.delete() }),
        createVod = { FakeHost(it) },
    )

    @Test fun aVideoStartsASessionAndJoinsARunningOne() {
        val s = session()
        assertEquals(AddOutcome.Added, s.addVod(vod("1")))
        val host = assertNotNull(s.current.value)
        assertEquals(AddOutcome.Added, s.addVod(vod("2")))
        assertSame(host, s.current.value)
        s.open("shroud")
        assertEquals(listOf("shroud", "shroud", "shroud"), host.layout.tiles.map { it.login })
        assertEquals(listOf(true, true, false), host.layout.tiles.map { it.isVod })
    }

    @Test fun theSameVideoIsReportedAndAFullScreenRefuses() {
        val s = session()
        s.addVod(vod("1"))
        assertIs<AddOutcome.AlreadyShown>(s.addVod(vod("1")))
        s.addVod(vod("2"))
        s.addVod(vod("3"))
        s.open("live")
        assertEquals(AddOutcome.Full, s.addVod(vod("4")))
        assertEquals(AddOutcome.Full, s.popOutVod(vod("4")))
        assertEquals(4, s.current.value!!.layout.count)
    }

    @Test fun popOutVodAddsAndPopsOrPopsTheOneAlreadyShown() {
        val s = session()
        s.open("live")
        assertEquals(AddOutcome.Added, s.popOutVod(vod("1")))
        val host = s.current.value!!
        val tile = host.layout.tiles.single { it.isVod }
        assertTrue(host.layout.isPopped(tile.id))
        assertFalse(host.layout.isPopped(host.layout.tiles.first { !it.isVod }.id))
        // Docked again, then popped out once more by the same call.
        s.dock(tile.id)
        assertIs<AddOutcome.AlreadyShown>(s.popOutVod(vod("1")))
        assertTrue(host.layout.isPopped(tile.id))
        assertEquals(2, host.layout.count)
    }

    @Test fun aSessionStartedByAPoppedVideoSurvivesLeavingTheGridAndEndsWithTheLastTile() {
        val s = session()
        s.popOutVod(vod("1"))
        val host = s.current.value!!
        s.open("live")
        s.leaveGrid()
        assertSame(host, s.current.value)
        assertEquals(listOf(true), host.layout.tiles.map { it.isVod })
        assertTrue(s.closeTile(host.layout.tiles[0].id).isEmpty())
        assertNull(s.current.value)
        assertTrue(host.shutDown)
    }

    @Test fun endStopsVideoTilesToo() {
        val s = session()
        s.addVod(vod("1"))
        val host = s.current.value!!
        s.end()
        assertTrue(host.shutDown)
        assertNull(s.current.value)
    }
}
