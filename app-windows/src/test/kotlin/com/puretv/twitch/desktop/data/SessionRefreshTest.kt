package com.puretv.twitch.desktop.data

import com.puretv.twitch.core.di.TokenHolder
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression for "I need to relink PureTV at least once a day" (issue #25).
 *
 * Shipped Windows builds carried the placeholder client secret, so every refresh
 * came back `403 invalid client secret`. [DesktopSettingsStore.refreshIfNeeded]
 * treated any refusal as a revoked session and deleted it, so the first launch
 * after the ~4h access token expired always landed on the sign-in screen. Only a
 * dead refresh token may clear the session now.
 */
class SessionRefreshTest {

    private val now = 1_000_000L

    private fun withExpiredSession(block: suspend (DesktopSettingsStore, File) -> Unit) = runTest {
        val dir = Files.createTempDirectory("puretv-refresh-test").toFile()
        try {
            val store = DesktopSettingsStore(TokenHolder(), dir)
            store.saveTokens("old-access", "old-refresh", expiresAtEpochSeconds = now - 60, userId = "u1", login = "viewer")
            block(store, dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun twitchAnswering(status: HttpStatusCode, body: String) = HttpClient(MockEngine {
        respond(body, status, headersOf("Content-Type", "application/json"))
    })

    @Test
    fun refusedClientSecretKeepsTheSession() = withExpiredSession { store, dir ->
        store.refreshIfNeeded(twitchAnswering(HttpStatusCode.Forbidden, """{"status":403,"message":"invalid client secret"}"""), now)

        assertTrue(store.isLoggedIn, "a refused client secret must not sign the user out")
        assertEquals("old-refresh", DesktopSettingsStore(TokenHolder(), dir).loadTokens()?.refreshToken)
    }

    @Test
    fun serverErrorKeepsTheSession() = withExpiredSession { store, _ ->
        store.refreshIfNeeded(twitchAnswering(HttpStatusCode.InternalServerError, """{"status":500,"message":"Internal Server Error"}"""), now)

        assertTrue(store.isLoggedIn)
    }

    @Test
    fun deadRefreshTokenSignsOut() = withExpiredSession { store, _ ->
        store.refreshIfNeeded(twitchAnswering(HttpStatusCode.BadRequest, """{"status":400,"message":"Invalid refresh token"}"""), now)

        assertFalse(store.isLoggedIn, "a refresh token Twitch calls invalid can never work again")
    }

    @Test
    fun successfulRefreshPersistsTheNewTokens() = withExpiredSession { store, dir ->
        store.refreshIfNeeded(
            twitchAnswering(HttpStatusCode.OK, """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":14000}"""),
            now,
        )

        val restored = DesktopSettingsStore(TokenHolder(), dir).loadTokens()
        assertEquals("new-access", restored?.accessToken)
        assertEquals("new-refresh", restored?.refreshToken)
        assertEquals(now + 14000, restored?.expiresAtEpochSeconds)
        assertEquals("viewer", restored?.login)
    }
}
