package com.puretv.twitch.core.stream

import com.puretv.twitch.core.CallCounter
import com.puretv.twitch.core.api.TwitchApiClient
import com.puretv.twitch.core.chat.BadgeRepository
import com.puretv.twitch.core.di.buildKtorClient
import com.puretv.twitch.core.model.EmoteProvider
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Signed out, Twitch's own chat badges and emotes come from anonymous GQL (Helix
 * refuses both without a user token); signed in, they stay on Helix. Fixtures are
 * trimmed from live anonymous captures on 2026-10-20.
 */
class SignedOutChatRoutingTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val helixBadges =
        """{"data":[{"set_id":"moderator","versions":[{"id":"1","image_url_1x":"h1","image_url_2x":"h2","image_url_4x":"h4","title":"Moderator"}]}]}"""
    private val helixEmotes = """{"data":[{"id":"25","name":"Kappa","format":["static"]}]}"""

    private val gqlGlobalBadges = """
        {"data":{"badges":[
          {"setID":"moderator","version":"1","title":"Moderator","imageURL":"https://static-cdn.jtvnw.net/badges/v1/mod/2"},
          {"setID":"turbo","version":"1","title":"Turbo","imageURL":"https://static-cdn.jtvnw.net/badges/v1/turbo/2"},
          {"setID":"blank","version":"1","title":"No art","imageURL":""},
          null
        ]}}
    """.trimIndent()

    private val gqlChannel = """
        {"data":{"user":{
          "broadcastBadges":[
            {"setID":"subscriber","version":"0","title":"Subscriber","imageURL":"https://static-cdn.jtvnw.net/badges/v1/sub0/2"},
            {"setID":"subscriber","version":"3","title":"3-Month Subscriber","imageURL":"https://static-cdn.jtvnw.net/badges/v1/sub3/2"}
          ],
          "subscriptionProducts":[
            {"emotes":[{"id":"emotesv2_a","token":"shroudWave","assetType":"ANIMATED"},{"id":"emotesv2_b","token":"shroudY","assetType":"STATIC"}]},
            {"emotes":[{"id":"emotesv2_b","token":"shroudY","assetType":"STATIC"},{"id":"emotesv2_c","token":"shroudGun","assetType":"STATIC"}]}
          ]}}}
    """.trimIndent()

    private val gqlGlobalEmotes =
        """{"data":{"emoteSet":{"emotes":[{"id":"25","token":"Kappa","assetType":"STATIC"},{"id":"","token":"Broken"}]}}}"""

    private class Harness(val api: TwitchApiClient, val helixCalls: CallCounter, val gqlCalls: CallCounter)

    private fun harness(
        gqlStatus: HttpStatusCode = HttpStatusCode.OK,
        wired: Boolean = true,
        token: () -> String?,
    ): Harness {
        val helix = CallCounter()
        val gql = CallCounter()
        val engine = MockEngine { request ->
            when (request.url.host) {
                "api.twitch.tv" -> {
                    helix.incrementAndGet()
                    respond(if (request.url.encodedPath.contains("badges")) helixBadges else helixEmotes, HttpStatusCode.OK, jsonHeaders)
                }
                "gql.twitch.tv" -> {
                    gql.incrementAndGet()
                    val body = (request.body as io.ktor.http.content.TextContent).text
                    val reply = when {
                        "emoteSet" in body -> gqlGlobalEmotes
                        "broadcastBadges" in body -> gqlChannel
                        else -> gqlGlobalBadges
                    }
                    respond(reply, gqlStatus, jsonHeaders)
                }
                else -> error("unexpected host ${request.url.host}")
            }
        }
        val client = buildKtorClient(engine)
        val api = TwitchApiClient(client, token)
        if (wired) api.anonymousChat = TwitchGqlDirectoryClient(client)
        return Harness(api, helix, gql)
    }

    @Test fun signedOutBadgesAreRegroupedIntoSetsAndIndexed() = runTest {
        val h = harness { null }
        val index = BadgeRepository(h.api).loadForChannel("37402112")
        assertEquals(0, h.helixCalls.get(), "no token, nothing may reach Helix")
        assertEquals("https://static-cdn.jtvnw.net/badges/v1/mod/2", index.resolve("moderator", "1")?.url)
        assertEquals("Turbo", index.resolve("turbo", "1")?.title)
        assertEquals("3-Month Subscriber", index.resolve("subscriber", "3")?.title)
        assertEquals(null, index.resolve("blank", "1"), "a badge with no art is dropped")
    }

    @Test fun signedOutEmotesAreDeduplicatedAndMarkedAnimated() = runTest {
        val h = harness { null }
        val channel = h.api.getChannelTwitchEmotes("37402112")
        assertEquals(listOf("shroudWave", "shroudY", "shroudGun"), channel.map { it.name })
        assertTrue(channel.first().animated)
        assertTrue(channel.first().url.contains("/animated/"))
        assertTrue(channel.all { it.provider == EmoteProvider.TWITCH })
        val global = h.api.getGlobalEmotes()
        assertEquals(listOf("Kappa"), global.map { it.name })
        assertEquals(0, h.helixCalls.get())
    }

    @Test fun signedInStaysOnHelix() = runTest {
        val h = harness { "token" }
        BadgeRepository(h.api).loadForChannel("37402112")
        h.api.getGlobalEmotes()
        h.api.getChannelTwitchEmotes("37402112")
        assertEquals(0, h.gqlCalls.get(), "a signed-in viewer adds no GQL calls")
        assertTrue(h.helixCalls.get() >= 4)
    }

    @Test fun aBlankTokenCountsAsSignedOut() = runTest {
        val h = harness { "" }
        BadgeRepository(h.api).loadForChannel("37402112")
        assertEquals(0, h.helixCalls.get())
    }

    @Test fun withoutTheAnonymousSourceSignedOutKeepsTheOldBehaviour() = runTest {
        val h = harness(wired = false) { null }
        BadgeRepository(h.api).loadForChannel("37402112")
        assertEquals(0, h.gqlCalls.get())
        assertTrue(h.helixCalls.get() > 0)
    }

    @Test fun aFailingGqlCallDegradesToNothing() = runTest {
        val h = harness(gqlStatus = HttpStatusCode.NotFound) { null }
        assertEquals(null, BadgeRepository(h.api).loadForChannel("1").resolve("moderator", "1"))
        assertEquals(emptyList(), h.api.getGlobalEmotes())
        assertEquals(emptyList(), h.api.getChannelTwitchEmotes("1"))
    }
}
