package com.puretv.twitch.core.api

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnauthorizedRetryTest {
    @Test fun successPassesThroughWithoutRenewing() = runTest {
        var renewals = 0
        val result = retryOnceIfUnauthorized(renew = { renewals++ }) { "live" }
        assertEquals("live", result)
        assertEquals(0, renewals)
    }

    @Test fun unauthorizedRenewsOnceAndRetries() = runTest {
        var renewals = 0
        var calls = 0
        val result = retryOnceIfUnauthorized(renew = { renewals++ }) {
            calls++
            if (calls == 1) throw HelixApiException(401, "Unauthorized")
            "live"
        }
        assertEquals("live", result)
        assertEquals(1, renewals)
        assertEquals(2, calls)
    }

    @Test fun secondUnauthorizedIsThrownAfterOneRenewal() = runTest {
        var renewals = 0
        var calls = 0
        val e = assertFailsWith<HelixApiException> {
            retryOnceIfUnauthorized(renew = { renewals++ }) {
                calls++
                throw HelixApiException(401, "Unauthorized")
            }
        }
        assertEquals(401, e.statusCode)
        assertEquals(1, renewals)
        assertEquals(2, calls)
    }

    @Test fun otherFailuresAreNotRetried() = runTest {
        var renewals = 0
        var calls = 0
        assertFailsWith<HelixApiException> {
            retryOnceIfUnauthorized(renew = { renewals++ }) {
                calls++
                throw HelixApiException(500, "Internal Server Error")
            }
        }
        assertFailsWith<IllegalStateException> {
            retryOnceIfUnauthorized(renew = { renewals++ }) { error("network down") }
        }
        assertEquals(0, renewals)
        assertEquals(1, calls)
    }
}
