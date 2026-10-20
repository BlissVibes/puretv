package com.puretv.twitch.core.vod

import com.puretv.twitch.core.stream.GqlPlaybackTokenException
import com.puretv.twitch.core.stream.GqlVideoMissingException
import com.puretv.twitch.core.stream.TwitchGqlClient
import com.puretv.twitch.core.stream.UsherPlaylistException
import com.puretv.twitch.core.stream.VodResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VodFailuresTest {

    private val tokenJson =
        """{"data":{"videoPlaybackAccessToken":{"value":"{}","signature":"sig"}}}"""

    private val validMaster = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=8029169,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",VIDEO="chunked",FRAME-RATE=60.000
        https://vod-edge.example/chunked/index-dvr.m3u8
    """.trimIndent()

    private fun resolver(usherStatus: HttpStatusCode, usherBody: String, token: String = tokenJson): VodResolver {
        val client = HttpClient(MockEngine { request ->
            if (request.url.host.contains("gql")) {
                respond(token, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(usherBody, usherStatus, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        return VodResolver(client, TwitchGqlClient(client))
    }

    @Test
    fun subscriberOnlyVodSaysSo() = runTest {
        val body = """[{"url":"/vod/123.m3u8","error":"No Permission","error_code":"vod_manifest_restricted","type":"error"}]"""
        val failure = assertFails { resolver(HttpStatusCode.Forbidden, body).resolveVodMasterPlaylist("123") }
        assertIs<UsherPlaylistException>(failure)
        assertEquals(VodFailures.SUBSCRIBERS_ONLY, VodFailures.message(failure))
        assertTrue(VodFailures.isPermanent(failure))
    }

    @Test
    fun deletedVodSaysItIsGone() = runTest {
        val body = """[{"url":"/vod/123.m3u8","error":"vod does not exist","error_code":"vod_manifest_not_found","type":"error"}]"""
        val failure = assertFails { resolver(HttpStatusCode.NotFound, body).resolveVodMasterPlaylist("123") }
        assertEquals(VodFailures.GONE, VodFailures.message(failure))
    }

    @Test
    fun aJsonBodyWithSuccessStatusIsNotAPlaylist() = runTest {
        val failure = assertFails { resolver(HttpStatusCode.OK, """{"error":"nope"}""").resolveVodMasterPlaylist("123") }
        assertIs<UsherPlaylistException>(failure)
    }

    @Test
    fun validMasterResolves() = runTest {
        val result = resolver(HttpStatusCode.OK, validMaster).resolveVodMasterPlaylist("123")
        assertEquals(1, result.variants.size)
        assertTrue(result.masterUrl.contains("/vod/123.m3u8"), result.masterUrl)
    }

    @Test
    fun aRealGqlAnswerWithNoVideoAndNoErrorsIsGone() = runTest {
        val failure = assertFails {
            resolver(HttpStatusCode.OK, validMaster, token = """{"data":{"videoPlaybackAccessToken":null}}""")
                .resolveVodMasterPlaylist("123")
        }
        assertTrue(VodFailures.isConfirmedGone(failure))
    }

    @Test
    fun aRealGqlAnswerWithErrorsIsNeverTreatedAsGone() = runTest {
        for (body in listOf(
            """{"errors":[{"message":"failed integrity check"}],"data":{"videoPlaybackAccessToken":null}}""",
            """{"errors":[{"message":"service timeout"}]}""",
            "not json at all",
        )) {
            val failure = assertFails { resolver(HttpStatusCode.OK, validMaster, token = body).resolveVodMasterPlaylist("123") }
            assertFalse(VodFailures.isConfirmedGone(failure), body)
        }
    }

    @Test
    fun aCleanAnswerWithNoVideoMeansItIsGone() {
        val failure = GqlVideoMissingException("no token and no errors")
        assertEquals(VodFailures.GONE, VodFailures.message(failure))
        assertTrue(VodFailures.isConfirmedGone(failure))
    }

    @Test
    fun noTokenWithErrorsIsOnlyABlip() {
        val failure = GqlPlaybackTokenException("no token, GQL reported errors")
        assertEquals(VodFailures.COULD_NOT_LOAD, VodFailures.message(failure))
        assertFalse(VodFailures.isConfirmedGone(failure))
        assertFalse(VodFailures.isPermanent(failure))
    }

    @Test
    fun onlyAConfirmedDeletionIsSafeToForget() {
        assertTrue(VodFailures.isConfirmedGone(UsherPlaylistException("usher 404", status = 404, body = "vod does not exist")))
        assertFalse(VodFailures.isConfirmedGone(UsherPlaylistException("usher 503", status = 503, body = "")))
        assertFalse(VodFailures.isConfirmedGone(UsherPlaylistException("usher 403", status = 403, body = "vod_manifest_restricted")))
        assertFalse(VodFailures.isConfirmedGone(null))
    }

    @Test
    fun anOutageIsNotPermanent() {
        val outage = UsherPlaylistException("usher 503", status = 503, body = "")
        assertFalse(VodFailures.isPermanent(outage))
        assertTrue(VodFailures.message(outage).startsWith("Twitch isn't responding"), VodFailures.message(outage))
    }
}
