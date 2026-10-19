package com.puretv.twitch.core.stream

import com.puretv.twitch.core.api.HelixApiException
import com.puretv.twitch.core.api.RateLimitedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamFailuresTest {
    /** A real, playable token's value: it mentions geoblock and restricted in keys that are empty. */
    private val normalToken =
        """{"adblock":false,"authorization":{"forbidden":false,"reason":""},"blackout_enabled":false,""" +
            """"channel":"somechannel","chansub":{"restricted_bitrates":[],"view_until":1924905600},""" +
            """"ci_gb":false,"geoblock_reason":"","device_id":null,"expires":1760000000}"""

    private fun usher(status: Int, body: String, token: String = normalToken) =
        UsherPlaylistException("usher refused", status = status, body = body, tokenValue = token)

    private class UnknownHostException(message: String) : Exception(message)

    @Test
    fun anOfflineChannelSaysSo() {
        val failure = StreamFailures.classify(
            usher(404, """[{"url":"/api/channel/hls/x.m3u8","error":"Can not find channel","error_code":"not_found"}]"""),
        )
        assertEquals(FailureKind.OFFLINE, failure.kind)
    }

    @Test
    fun aRegionBlockIsNamed() {
        val failure = StreamFailures.classify(
            usher(403, """[{"error":"Content Restricted In Region","error_code":"content_geoblocked"}]"""),
        )
        assertEquals(FailureKind.REGION_BLOCKED, failure.kind)
    }

    @Test
    fun aForbiddenTokenMeansRestricted() {
        val token = normalToken.replace(
            "\"forbidden\":false,\"reason\":\"\"",
            "\"forbidden\":true,\"reason\":\"UNAUTHORIZED_ENTITLEMENTS\"",
        )
        assertEquals(FailureKind.RESTRICTED, StreamFailures.classify(usher(403, "[]", token)).kind)
    }

    @Test
    fun theKeysInANormalTokenDoNotCountAsReasons() {
        // A 403 with nothing recognisable must not be blamed on region or subscriptions
        // just because every token has "geoblock_reason" and "restricted_bitrates" keys.
        assertEquals(FailureKind.UNKNOWN, StreamFailures.classify(usher(403, """[{"error":"Signature is invalid"}]""")).kind)
    }

    @Test
    fun serverErrorsMeanTwitchIsDown() {
        assertEquals(FailureKind.TWITCH_DOWN, StreamFailures.classify(usher(503, "<html>")).kind)
        assertEquals(FailureKind.TWITCH_DOWN, StreamFailures.classify(HelixApiException(502, "Bad Gateway")).kind)
    }

    @Test
    fun anExpiredSignInIsNamed() {
        assertEquals(FailureKind.SESSION_EXPIRED, StreamFailures.classify(HelixApiException(401, "Unauthorized")).kind)
    }

    @Test
    fun networkFailuresAnywhereInTheChainMeanNoInternet() {
        val wrapped = IllegalStateException("load failed", UnknownHostException("gql.twitch.tv"))
        assertEquals(FailureKind.NO_INTERNET, StreamFailures.classify(wrapped).kind)
    }

    @Test
    fun rateLimitsAreNamed() {
        assertEquals(FailureKind.RATE_LIMITED, StreamFailures.classify(RateLimitedException(null)).kind)
    }

    @Test
    fun sentencesReadNaturally() {
        val failure = Failure(FailureKind.TWITCH_DOWN, "")
        assertEquals(
            "Couldn't load categories. Twitch isn't responding. Twitch is having trouble right now. Try again in a minute.",
            failure.sentence("Couldn't load categories"),
        )
        assertTrue(Failure(FailureKind.UNKNOWN, "").sentence("Search failed").startsWith("Search failed. Try again"))
    }
}
