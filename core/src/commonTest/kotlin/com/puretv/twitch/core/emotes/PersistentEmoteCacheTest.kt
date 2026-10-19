package com.puretv.twitch.core.emotes

import com.puretv.twitch.core.CallCounter
import com.puretv.twitch.core.model.ChannelEmote
import com.puretv.twitch.core.model.EmoteProvider
import com.puretv.twitch.core.storage.TextStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

class PersistentEmoteCacheTest {
    private class MapStore : TextStore {
        val files = HashMap<String, String>()
        override suspend fun read(name: String): String? = files[name]
        override suspend fun write(name: String, text: String) { files[name] = text }
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val bttvGlobal = """[{"id":"b1","code":"FeelsGood","imageType":"png"}]"""
    private val sevenTvGlobal = """{"emotes":[{"id":"1","name":"catJAM","flags":0,"data":{"animated":true,"flags":0}}]}"""
    private val sevenTvChannel = """{"emote_set":{"emotes":[{"id":"9","name":"peepoHey","flags":0,"data":{"animated":false,"flags":0}}]}}"""
    private val bttvChannel = """{"channelEmotes":[],"sharedEmotes":[]}"""
    private val ffzChannel = """{"sets":{}}"""

    private fun client(sevenTvUp: () -> Boolean = { true }, calls: CallCounter? = null) = HttpClient(MockEngine { request ->
        calls?.incrementAndGet()
        val url = request.url.toString()
        when {
            "7tv.io" in url && !sevenTvUp() -> respond("down", HttpStatusCode.ServiceUnavailable)
            "7tv.io/v3/emote-sets/global" in url -> respond(sevenTvGlobal, HttpStatusCode.OK, jsonHeaders)
            "7tv.io/v3/users/twitch" in url -> respond(sevenTvChannel, HttpStatusCode.OK, jsonHeaders)
            "betterttv.net/3/cached/emotes/global" in url -> respond(bttvGlobal, HttpStatusCode.OK, jsonHeaders)
            "betterttv.net/3/cached/users" in url -> respond(bttvChannel, HttpStatusCode.OK, jsonHeaders)
            "frankerfacez" in url -> respond(ffzChannel, HttpStatusCode.OK, jsonHeaders)
            else -> respond("", HttpStatusCode.NotFound)
        }
    }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test
    fun listsSurviveARestart() = runTest {
        val store = MapStore()
        val emote = ChannelEmote("9", "peepoHey", "https://cdn.7tv.app/emote/9/4x.png", EmoteProvider.SEVENTV, animated = false, zeroWidth = true)
        PersistentEmoteCache(store).putChannelEmotes("123", listOf(emote))

        val restarted = PersistentEmoteCache(store)

        assertEquals(listOf(emote), restarted.channelEmotes("123"))
        assertNull(restarted.globalEmotes())
    }

    @Test
    fun aCorruptEntryIsAMiss() = runTest {
        val store = MapStore().apply { files["emotes-global.json"] = "{not json" }

        assertNull(PersistentEmoteCache(store).globalEmotes())
    }

    @Test
    fun theCachedCopyComesFirstThenTheFreshOne() = runTest {
        val store = MapStore()
        val stale = ChannelEmote("old", "OldEmote", "https://example/old", EmoteProvider.BTTV, animated = false)
        PersistentEmoteCache(store).putChannelEmotes("123", listOf(stale))

        val repo = EmoteRepository(client(), PersistentEmoteCache(store))
        val emitted = repo.thirdPartyEmotes("123", "somechannel").toList()

        assertEquals(2, emitted.size)
        assertEquals(listOf("OldEmote"), emitted[0].channel.map { it.name })
        assertEquals(listOf("peepoHey"), emitted[1].channel.map { it.name })
        assertEquals(setOf("FeelsGood", "catJAM"), emitted[1].global.map { it.name }.toSet())
    }

    @Test
    fun aFailingProviderKeepsItsCachedEmotes() = runTest {
        val store = MapStore()
        val cachedSevenTv = ChannelEmote("9", "peepoHey", "https://cdn.7tv.app/emote/9/4x.png", EmoteProvider.SEVENTV, animated = false)
        PersistentEmoteCache(store).putChannelEmotes("123", listOf(cachedSevenTv))

        val repo = EmoteRepository(client(sevenTvUp = { false }), PersistentEmoteCache(store))
        val loaded = repo.loadChannelEmotes("123", "somechannel")

        assertEquals(listOf("peepoHey"), loaded.map { it.name }, "7TV is down, so its cached emotes must stay")
    }

    @Test
    fun reJoiningAChannelSoonAfterSkipsTheNetwork() = runTest {
        val calls = CallCounter()
        val clock = TestTimeSource()
        val repo = EmoteRepository(client(calls = calls), PersistentEmoteCache(MapStore()), timeSource = clock)

        repo.loadChannelEmotes("123", "somechannel")
        val afterFirst = calls.get()
        repo.loadChannelEmotes("123", "somechannel")
        assertEquals(afterFirst, calls.get(), "a quick re-join reuses the fresh copy")

        clock += 11.minutes
        repo.loadChannelEmotes("123", "somechannel")
        assertEquals(afterFirst * 2, calls.get(), "an older copy is refreshed")
    }
}
