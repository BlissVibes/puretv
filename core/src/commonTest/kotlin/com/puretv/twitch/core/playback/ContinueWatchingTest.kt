package com.puretv.twitch.core.playback

import com.puretv.twitch.core.model.StreamInfo
import com.puretv.twitch.core.vod.WatchProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContinueWatchingTest {
    private fun channel(login: String) = RecentChannel(login, login.replaceFirstChar { it.uppercase() })
    private fun stream(login: String) =
        StreamInfo(id = "s-$login", userId = "u-$login", userLogin = login, userName = login, thumbnailUrl = "t-$login")
    private fun vod(id: String, position: Long = 60_000, duration: Long = 600_000, updatedAt: Long = 1) =
        WatchProgress(id, position, duration, updatedAt, title = "VOD $id", channelLogin = "ch")

    @Test fun liveChannelsComeFirstThenVodsThenOfflineChannels() {
        val items = ContinueWatching.build(
            recent = listOf(channel("off1"), channel("live1"), channel("off2"), channel("live2")),
            vods = listOf(vod("v1")),
            live = mapOf("live1" to stream("live1"), "live2" to stream("live2")),
        )
        assertEquals(
            listOf("live1", "live2", "vod:v1", "off1", "off2"),
            items.map { if (it is ContinueItem.Channel) it.login else "vod:" + (it as ContinueItem.Vod).progress.vodId },
        )
    }

    @Test fun aLiveChannelCarriesItsStreamForTheThumbnail() {
        val items = ContinueWatching.build(listOf(channel("a")), emptyList(), mapOf("a" to stream("a")))
        assertEquals("t-a", (items.single() as ContinueItem.Channel).stream?.thumbnailUrl)
    }

    @Test fun onlyResumableVodsAppearNewestFirst() {
        val items = ContinueWatching.build(
            recent = emptyList(),
            vods = listOf(
                vod("old", updatedAt = 1),
                vod("done", position = 590_000),
                vod("tooEarly", position = 1_000),
                vod("new", updatedAt = 9),
            ),
            live = emptyMap(),
        )
        assertEquals(listOf("new", "old"), items.map { (it as ContinueItem.Vod).progress.vodId })
    }

    @Test fun offlineChannelsAreKeptToAShortTail() {
        val items = ContinueWatching.build(List(8) { channel("c$it") }, emptyList(), emptyMap())
        assertEquals(ContinueWatching.MAX_OFFLINE, items.size)
        assertEquals(listOf("c0", "c1", "c2", "c3"), items.map { (it as ContinueItem.Channel).login })
    }

    @Test fun theRowIsBounded() {
        val live = (0 until 20).associate { "c$it" to stream("c$it") }
        val items = ContinueWatching.build(List(20) { channel("c$it") }, emptyList(), live)
        assertTrue(items.size <= ContinueWatching.MAX_ITEMS)
    }

    @Test fun liveLookupSkipsWhatIsAlreadyKnownLive() {
        val recent = listOf(channel("Alice"), channel("bob"), channel("carol"))
        assertEquals(listOf("bob", "carol"), ContinueWatching.loginsToCheck(recent, setOf("alice")))
        assertEquals(emptyList(), ContinueWatching.loginsToCheck(recent, setOf("alice", "BOB", "carol")))
    }

    @Test fun liveLookupIsCapped() {
        val recent = List(30) { channel("c$it") }
        assertEquals(ContinueWatching.LOOKUP_LIMIT, ContinueWatching.loginsToCheck(recent, emptySet()).size)
    }
}
