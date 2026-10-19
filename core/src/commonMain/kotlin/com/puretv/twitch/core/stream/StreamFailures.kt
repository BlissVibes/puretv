package com.puretv.twitch.core.stream

import com.puretv.twitch.core.api.HelixApiException
import com.puretv.twitch.core.api.RateLimitedException

/**
 * Why something failed, in terms a viewer can act on. Before this, every failure
 * read "Check your connection" (or the raw usher text), so a subscriber-only
 * stream looked like a broken app and a Twitch outage sent people restarting
 * their Wi-Fi.
 */
enum class FailureKind(val title: String, val advice: String) {
    OFFLINE("This channel is offline", "Check back when they go live."),
    RESTRICTED(
        "This stream is restricted",
        "The channel limits who can watch it, for example to subscribers, and PureTV can't play it.",
    ),
    REGION_BLOCKED("Not available where you are", "Twitch blocks this stream in your region."),
    TWITCH_DOWN("Twitch isn't responding", "Twitch is having trouble right now. Try again in a minute."),
    RATE_LIMITED("Twitch asked us to slow down", "Wait a minute, then try again."),
    SESSION_EXPIRED("Your Twitch sign-in expired", "Sign in again to carry on."),
    NO_INTERNET("Can't reach Twitch", "Check your internet connection, then try again."),
    UNKNOWN("Something went wrong", "Try again in a moment."),
}

/** A classified failure plus the raw detail, which stays useful for logs and diagnostics. */
data class Failure(val kind: FailureKind, val detail: String) {
    /** One line for a list screen: "Couldn't load categories. Twitch isn't responding. Try again in a minute." */
    fun sentence(context: String): String = "$context. ${reason()}"

    /** A full message for a player screen: what happened, then what to do. */
    fun message(): String = "${kind.title}. ${kind.advice}"

    /** The cause and what to do, without a context prefix. */
    fun reason(): String = when (kind) {
        FailureKind.UNKNOWN -> kind.advice
        else -> "${kind.title}. ${kind.advice}"
    }
}

object StreamFailures {
    /**
     * Classifies a failure from stream resolution, playback or a Helix call.
     * Signals, strongest first: a network-level exception anywhere in the cause
     * chain, Helix's status code, then usher's status plus its JSON error and the
     * playback token's `authorization` block.
     */
    fun classify(error: Throwable?): Failure {
        val detail = error?.message ?: error?.let { it::class.simpleName }.orEmpty()
        if (error == null) return Failure(FailureKind.UNKNOWN, detail)
        if (isNetworkFailure(error)) return Failure(FailureKind.NO_INTERNET, detail)

        val root = causes(error)
        root.firstNotNullOfOrNull { it as? RateLimitedException }?.let { return Failure(FailureKind.RATE_LIMITED, detail) }
        root.firstNotNullOfOrNull { it as? HelixApiException }?.let { helix ->
            return Failure(
                when {
                    helix.statusCode == 401 -> FailureKind.SESSION_EXPIRED
                    helix.statusCode >= 500 -> FailureKind.TWITCH_DOWN
                    else -> FailureKind.UNKNOWN
                },
                detail,
            )
        }
        root.firstNotNullOfOrNull { it as? UsherPlaylistException }?.let { usher ->
            return Failure(classifyUsher(usher.status, usher.body, usher.tokenValue), detail)
        }
        return Failure(FailureKind.UNKNOWN, detail)
    }

    /**
     * usher answers a refused stream with a JSON array naming the reason, for
     * example `[{"error":"Content Restricted In Region","error_code":"content_geoblocked"}]`.
     * The playback token carries an `authorization` block with `forbidden` and a
     * `reason`. Matching is on keywords, not exact codes, because Twitch rewords
     * these: anything unrecognised falls back to a status-based answer.
     */
    internal fun classifyUsher(status: Int?, body: String, tokenValue: String): FailureKind {
        val text = body.lowercase()
        // Only the token's meaningful fields: every normal token also carries keys
        // such as "geoblock_reason":"" and "restricted_bitrates":[], so matching
        // words across the whole token would misfire.
        val tokenForbidden = FORBIDDEN.containsMatchIn(tokenValue)
        val tokenGeoReason = GEO_REASON.find(tokenValue)?.groupValues?.get(1).orEmpty()
        return when {
            "geoblock" in text || "region" in text || tokenGeoReason.isNotBlank() -> FailureKind.REGION_BLOCKED
            "subscri" in text || "restricted" in text || "unauthorized_entitlements" in text || tokenForbidden ->
                FailureKind.RESTRICTED
            status == 404 || "not_found" in text || "does not exist" in text || "offline" in text -> FailureKind.OFFLINE
            status != null && status >= 500 -> FailureKind.TWITCH_DOWN
            else -> FailureKind.UNKNOWN
        }
    }

    private val FORBIDDEN = Regex("\"forbidden\"\\s*:\\s*true")
    private val GEO_REASON = Regex("\"geoblock_reason\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * Connection failures carry platform types (java.net on the JVM, Ktor's own
     * timeout types), which common code cannot name, so they are matched by class
     * name anywhere in the cause chain.
     */
    private fun isNetworkFailure(error: Throwable): Boolean =
        causes(error).any { (it::class.simpleName ?: "") in NETWORK_EXCEPTIONS }

    private fun causes(error: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>()
        var current: Throwable? = error
        while (current != null && current !in out && out.size < 8) {
            out += current
            current = current.cause
        }
        return out
    }

    private val NETWORK_EXCEPTIONS = setOf(
        "UnknownHostException",
        "ConnectException",
        "NoRouteToHostException",
        "SocketTimeoutException",
        "SocketException",
        "SSLException",
        "SSLHandshakeException",
        "ConnectTimeoutException",
        "HttpRequestTimeoutException",
        "UnresolvedAddressException",
    )
}
