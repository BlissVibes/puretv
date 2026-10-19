package com.puretv.twitch.core.session

/**
 * When to renew a Twitch access token (they last about four hours). The phone
 * checks this on launch, on every return to the app and every few minutes while
 * it runs, so it must be cheap and must not renew a token that is still fresh:
 * each renewal rotates the refresh token, and needless rotations only widen the
 * window for two callers to race each other.
 */
object SessionRefreshPolicy {
    /** Renew this long before expiry, to cover clock skew and a request in flight. */
    const val LEEWAY_SECONDS = 300L

    /**
     * True when the token expiring at [expiresAtEpochSeconds] should be renewed at
     * [nowEpochSeconds]. An unknown expiry (0, for a session saved before expiries
     * were stored) renews once, which then records the real expiry.
     */
    fun shouldRefresh(expiresAtEpochSeconds: Long, nowEpochSeconds: Long): Boolean =
        expiresAtEpochSeconds <= 0L || nowEpochSeconds >= expiresAtEpochSeconds - LEEWAY_SECONDS
}
