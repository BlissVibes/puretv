package com.puretv.twitch.core.api

/**
 * Runs [call], and when Helix answers 401 runs [renew] once and tries [call] one
 * more time.
 *
 * For background callers (the go-live poll on phone and TV) that may run long after
 * the access token died early: Twitch can invalidate a token before its stored
 * expiry, and nothing else would renew it until the viewer opened the app. Any other
 * failure, and a second 401 after renewing, is thrown as is, so the caller's own
 * error handling still decides what a failure means. [renew] is expected to be
 * fail-soft and rate-limited by its owner (both apps' token refreshers are).
 */
suspend fun <T> retryOnceIfUnauthorized(renew: suspend () -> Unit, call: suspend () -> T): T =
    try {
        call()
    } catch (e: HelixApiException) {
        if (e.statusCode != 401) throw e
        renew()
        call()
    }
