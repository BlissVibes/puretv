package com.puretv.twitch.desktop.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The /variant route must only proxy the Twitch HLS CDN, or it is an open
 * localhost SSRF proxy. See [LocalStreamProxy.isAllowedVariantHost].
 */
class LocalStreamProxyHostTest {

    @Test fun allowsTwitchCdnHosts() {
        assertTrue(LocalStreamProxy.isAllowedVariantHost("https://video-edge-abc.abs.hls.ttvnw.net/v1/segment/x.ts"))
        assertTrue(LocalStreamProxy.isAllowedVariantHost("https://usher.ttvnw.net/api/channel/hls/x.m3u8"))
        assertTrue(LocalStreamProxy.isAllowedVariantHost("https://ttvnw.net/x"))
    }

    @Test fun blocksSsrfTargets() {
        assertFalse(LocalStreamProxy.isAllowedVariantHost("http://169.254.169.254/latest/meta-data/"))
        assertFalse(LocalStreamProxy.isAllowedVariantHost("http://127.0.0.1:7979/diag"))
        assertFalse(LocalStreamProxy.isAllowedVariantHost("http://localhost/secret"))
    }

    @Test fun blocksSuffixSpoof() {
        // ttvnw.net.evil.com must NOT be treated as a Twitch host.
        assertFalse(LocalStreamProxy.isAllowedVariantHost("https://ttvnw.net.evil.com/x"))
        assertFalse(LocalStreamProxy.isAllowedVariantHost("https://eviltvnw.net/x"))
    }

    @Test fun playerUrlsNameTheAddressTheProxyListensOn() {
        // Not "localhost": that can resolve to ::1, where another program may hold the port.
        assertTrue(LocalStreamProxy.streamUrl("forsen").startsWith("http://127.0.0.1:7979/stream?"))
        assertTrue(LocalStreamProxy.tileStreamUrl("forsen", 720).startsWith("http://127.0.0.1:7979/stream?"))
    }

    @Test fun onlyLoopbackHostHeadersAreServed() {
        assertTrue(LocalStreamProxy.isAllowedHostHeader("127.0.0.1:7979"))
        assertTrue(LocalStreamProxy.isAllowedHostHeader("localhost:7979"))
        assertTrue(LocalStreamProxy.isAllowedHostHeader("LOCALHOST"))
        // DNS rebinding: a page on evil.example resolving to 127.0.0.1 sends its own name.
        assertFalse(LocalStreamProxy.isAllowedHostHeader("evil.example:7979"))
        assertFalse(LocalStreamProxy.isAllowedHostHeader("127.0.0.1.evil.example"))
        assertFalse(LocalStreamProxy.isAllowedHostHeader(null))
        assertFalse(LocalStreamProxy.isAllowedHostHeader(""))
    }

    @Test fun rejectsGarbage() {
        assertFalse(LocalStreamProxy.isAllowedVariantHost("not a url"))
        assertFalse(LocalStreamProxy.isAllowedVariantHost(""))
    }
}
