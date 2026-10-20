package com.puretv.twitch.desktop.ui

import com.puretv.twitch.core.api.TwitchApiClient
import com.puretv.twitch.core.di.TokenHolder
import com.puretv.twitch.core.di.buildKtorClient
import com.puretv.twitch.core.repository.ChannelRepository
import com.puretv.twitch.core.repository.StreamRepository
import com.puretv.twitch.core.stream.StreamResolver
import com.puretv.twitch.core.stream.TwitchGqlClient
import com.puretv.twitch.core.stream.TwitchGqlDirectoryClient
import com.puretv.twitch.desktop.data.DesktopSettingsStore
import com.puretv.twitch.desktop.data.FollowStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wave 4, Feature 5: Home works without signing in. Signed out it fills from
 * the anonymous GQL directory (live streams plus a category shelf) and never
 * touches Helix; signed in it stays on Helix and skips the category call.
 */
class HomeSignedOutTest {
    private val tmp: File = Files.createTempDirectory("home-signed-out-test").toFile()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private val gqlStreams =
        """{"data":{"streams":{"edges":[{"cursor":"c1","node":{"id":"1","type":"live","viewersCount":5,""" +
            """"broadcaster":{"id":"10","login":"caseoh_","displayName":"caseoh_"}}}],"pageInfo":{"hasNextPage":false}}}}"""
    private val gqlGames =
        """{"data":{"games":{"edges":[{"node":{"id":"509658","name":"Just Chatting","displayName":"Just Chatting"}}]}}}"""
    private val helixStreams =
        """{"data":[{"id":"2","user_id":"20","user_login":"helixer","user_name":"Helixer","viewer_count":7}]}"""

    private fun <T> StateFlow<T>.awaitMatching(predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(5_000) { first(predicate) } }

    private class Setup(val vm: HomeViewModel, val requests: List<String>, val settings: DesktopSettingsStore)

    private fun setup(signedIn: Boolean): Setup {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            val host = request.url.host
            requests += host + request.url.encodedPath
            when {
                host == "gql.twitch.tv" -> {
                    val body = (request.body as io.ktor.http.content.TextContent).text
                    respond(if (body.contains("games(")) gqlGames else gqlStreams, HttpStatusCode.OK, jsonHeaders)
                }
                request.url.encodedPath.endsWith("/streams") -> respond(helixStreams, HttpStatusCode.OK, jsonHeaders)
                else -> respond("""{"data":[]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val client = buildKtorClient(engine)
        val tokenHolder = TokenHolder()
        val settings = DesktopSettingsStore(tokenHolder, File(tmp, "settings").apply { mkdirs() })
        if (signedIn) {
            settings.saveTokens("access-tok", "refresh-tok", expiresAtEpochSeconds = 9_999_999_999L, userId = "u1", login = "me")
        }
        val api = TwitchApiClient(client) { tokenHolder.current() }
        val directory = TwitchGqlDirectoryClient(client)
        val vm = HomeViewModel(
            streamRepository = StreamRepository(api, StreamResolver(client, TwitchGqlClient(client)), directory),
            channelRepository = ChannelRepository(api, directory),
            followStore = FollowStore(File(tmp, "follows").apply { mkdirs() }),
            settingsStore = settings,
        )
        return Setup(vm, requests, settings)
    }

    @Test fun signedOutHomeShowsLiveStreamsAndCategoriesFromGql() {
        val s = setup(signedIn = false)
        val state = s.vm.state.awaitMatching { !it.isLoading && it.topStreams.isNotEmpty() && it.topGames.isNotEmpty() }

        assertFalse(state.isLoggedIn)
        assertEquals(listOf("caseoh_"), state.topStreams.map { it.userLogin })
        assertEquals(listOf("Just Chatting"), state.topGames.map { it.name })
        assertFalse(state.loadFailed)
        assertTrue(s.requests.none { it.startsWith("api.twitch.tv") }, "signed out must not call Helix: ${s.requests}")
        s.vm.dispose()
    }

    @Test fun signedInHomeStaysOnHelixAndSkipsTheCategoryShelf() {
        val s = setup(signedIn = true)
        val state = s.vm.state.awaitMatching { !it.isLoading && it.topStreams.isNotEmpty() }

        assertTrue(state.isLoggedIn)
        assertEquals(listOf("helixer"), state.topStreams.map { it.userLogin })
        assertTrue(state.topGames.isEmpty())
        assertTrue(s.requests.none { it.startsWith("gql.twitch.tv") }, "signed in must not add GQL calls: ${s.requests}")
        assertTrue(s.requests.none { it.endsWith("/games/top") }, "signed in Home must not fetch categories: ${s.requests}")
        s.vm.dispose()
    }

    @Test fun signingInWhileHomeIsOpenDropsThePrompt() {
        val s = setup(signedIn = false)
        s.vm.state.awaitMatching { !it.isLoading }
        s.settings.saveTokens("access-tok", "refresh-tok", expiresAtEpochSeconds = 9_999_999_999L, userId = "u1", login = "me")
        val state = s.vm.state.awaitMatching { it.isLoggedIn }
        assertTrue(state.isLoggedIn)
        s.vm.dispose()
    }
}
