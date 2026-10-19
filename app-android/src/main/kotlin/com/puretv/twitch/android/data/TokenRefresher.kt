package com.puretv.twitch.android.data

import com.puretv.twitch.core.api.DeviceAuth
import com.puretv.twitch.core.api.TokenRefreshException
import com.puretv.twitch.core.repository.UserRepository
import com.puretv.twitch.core.session.SessionRefreshPolicy
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SECTION 03.2: keeps the Twitch sign-in alive for as long as the app runs.
 *
 * Twitch access tokens last about four hours; the stored refresh token (rotated
 * on each use) buys a fresh one. With background play the app easily outlives a
 * token, so [refreshIfNeeded] runs on launch, on every return to the app and on
 * a timer (see `PureTvApp`), and only touches the network when
 * [SessionRefreshPolicy] says the token is about to lapse.
 *
 * Callers overlap (a resume during the timer tick), so a [Mutex] serializes
 * them: two refreshes racing on one rotating refresh token would invalidate
 * each other.
 *
 * Fail-soft on network trouble: the session is kept and the next check tries
 * again. A [TokenRefreshException] marked revoked means Twitch no longer honours
 * this sign-in at all (password change, "disconnect" on twitch.tv), so the
 * session is cleared and the Welcome screen says Twitch signed the viewer out,
 * instead of every Helix call failing with a misleading network error.
 */
class TokenRefresher(
    private val httpClient: HttpClient,
    private val settingsStore: AppSettingsStore,
    private val userRepository: UserRepository,
) {
    private val mutex = Mutex()

    suspend fun refreshIfNeeded(nowEpochSeconds: Long = System.currentTimeMillis() / 1_000): Unit = mutex.withLock {
        val refresh = settingsStore.currentRefreshToken()?.takeIf { it.isNotBlank() }
        if (refresh != null &&
            SessionRefreshPolicy.shouldRefresh(settingsStore.tokenExpiresAtEpochSeconds(), nowEpochSeconds)
        ) {
            val current = settingsStore.flow.first()
            runCatching { DeviceAuth.refreshToken(httpClient, refresh) }
                .onSuccess { token ->
                    settingsStore.setSession(
                        accessToken = token.accessToken,
                        // Twitch may or may not rotate the refresh token; keep the old
                        // one if the response omits a new one.
                        refreshToken = token.refreshToken ?: refresh,
                        username = current.username,
                        userId = current.userId,
                        expiresAtEpochSeconds = nowEpochSeconds + token.expiresInSeconds,
                    )
                }
                .onFailure { e ->
                    if (e is TokenRefreshException && e.revoked) settingsStore.clearSession(revokedByTwitch = true)
                }
        }
        backfillIdentityIfMissing()
    }

    /**
     * If the user is signed in but the username/userId are blank, the post-login
     * identity lookup must have failed on a network blip (the session was saved
     * token-first, so the token still landed). Nothing else ever backfills it, so
     * chat would send under no name and Settings would show none. Retry the
     * lookup here, best-effort: a failure leaves the session untouched.
     */
    private suspend fun backfillIdentityIfMissing() {
        val settings = settingsStore.flow.first()
        if (settings.accessToken.isBlank()) return
        if (settings.username.isNotBlank() && settings.userId.isNotBlank()) return
        val me = runCatching { userRepository.getCurrentUser() }.getOrNull() ?: return
        settingsStore.setSession(
            accessToken = settings.accessToken,
            refreshToken = settingsStore.currentRefreshToken(),
            username = me.login,
            userId = me.id,
        )
    }
}
