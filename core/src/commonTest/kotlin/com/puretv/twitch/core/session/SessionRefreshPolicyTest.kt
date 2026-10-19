package com.puretv.twitch.core.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionRefreshPolicyTest {
    private val expiresAt = 1_000_000L

    @Test
    fun aFreshTokenIsLeftAlone() {
        assertFalse(SessionRefreshPolicy.shouldRefresh(expiresAt, expiresAt - 3_600))
        assertFalse(SessionRefreshPolicy.shouldRefresh(expiresAt, expiresAt - SessionRefreshPolicy.LEEWAY_SECONDS - 1))
    }

    @Test
    fun aTokenInsideTheLeewayOrExpiredIsRenewed() {
        assertTrue(SessionRefreshPolicy.shouldRefresh(expiresAt, expiresAt - SessionRefreshPolicy.LEEWAY_SECONDS))
        assertTrue(SessionRefreshPolicy.shouldRefresh(expiresAt, expiresAt + 60))
    }

    @Test
    fun anUnknownExpiryIsRenewedOnceToLearnIt() {
        assertTrue(SessionRefreshPolicy.shouldRefresh(0L, expiresAt))
    }
}
