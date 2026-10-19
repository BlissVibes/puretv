package com.puretv.twitch.core.stream

import com.puretv.twitch.core.CallCounter
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class PlayableStreamCacheTest {

    private val tokenJson = """{"data":{"streamPlaybackAccessToken":{"value":"{}","signature":"sig"}}}"""
    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=8029169,RESOLUTION=1920x1080,VIDEO="chunked",FRAME-RATE=60.000
        https://video-edge.example/chunked.m3u8
    """.trimIndent()

    private class Harness(val resolver: StreamResolver, val mints: CallCounter, val playerTypes: MutableList<String>)

    private fun harness(failFirstUsher: Boolean = false): Harness {
        val mints = CallCounter()
        val ushers = CallCounter()
        val playerTypes = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            if (request.url.host.contains("gql")) {
                mints.incrementAndGet()
                val body = (request.body as io.ktor.http.content.TextContent).text
                synchronized(playerTypes) { playerTypes += Regex("\"playerType\":\"(\\w+)\"").find(body)?.groupValues?.get(1).orEmpty() }
                respond(tokenJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                val n = ushers.incrementAndGet()
                if (failFirstUsher && n == 1) respond("[]", HttpStatusCode.Forbidden) else respond(master, HttpStatusCode.OK)
            }
        })
        return Harness(StreamResolver(client, TwitchGqlClient(client)), mints, playerTypes)
    }

    @Test
    fun aPrewarmedChannelOpensWithoutASecondMint() = runTest {
        val h = harness()
        val cache = PlayableStreamCache(h.resolver, scope = backgroundScope)

        cache.prewarm("SomeChannel")
        val opened = cache.get("somechannel")

        assertEquals(1, h.mints.get())
        assertEquals(master, opened.rawContent)
    }

    @Test
    fun resolvesAsThePopoutPlayerType() = runTest {
        val h = harness()
        PlayableStreamCache(h.resolver, scope = backgroundScope).get("somechannel")

        assertEquals(listOf(StreamResolver.PRIMARY_PLAYER_TYPE), h.playerTypes)
    }

    @Test
    fun expiredEntriesAreResolvedAgain() = runTest {
        val h = harness()
        val clock = TestTimeSource()
        val cache = PlayableStreamCache(h.resolver, timeSource = clock, scope = backgroundScope, ttl = 60.seconds)

        cache.get("somechannel")
        clock += 61.seconds
        cache.get("somechannel")

        assertEquals(2, h.mints.get())
    }

    @Test
    fun aFailedResolutionIsNotCached() = runTest {
        val h = harness(failFirstUsher = true)
        // Like production, a failed resolution must not tear down its siblings.
        val cache = PlayableStreamCache(h.resolver, scope = CoroutineScope(SupervisorJob(backgroundScope.coroutineContext[Job])))

        assertFails { cache.get("somechannel") }
        val retried = cache.get("somechannel")

        assertEquals(master, retried.rawContent)
        assertEquals(2, h.mints.get())
    }

    @Test
    fun invalidateForcesAFreshSession() = runTest {
        val h = harness()
        val cache = PlayableStreamCache(h.resolver, scope = backgroundScope)

        cache.get("somechannel")
        cache.invalidate("somechannel")
        cache.get("somechannel")

        assertEquals(2, h.mints.get())
    }

    @Test
    fun masterBodyIsServedOnlyForTheExactUrlWhileFresh() = runTest {
        val h = harness()
        val clock = TestTimeSource()
        val cache = PlayableStreamCache(h.resolver, timeSource = clock, scope = backgroundScope, ttl = 60.seconds)

        val resolved = cache.get("somechannel")

        assertEquals(master, cache.masterBody(resolved.masterUrl))
        assertNull(cache.masterBody(resolved.masterUrl + "&p=1"))
        clock += 61.seconds
        assertNull(cache.masterBody(resolved.masterUrl))
    }
}
