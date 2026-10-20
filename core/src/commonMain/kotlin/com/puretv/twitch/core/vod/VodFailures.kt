package com.puretv.twitch.core.vod

import com.puretv.twitch.core.stream.FailureKind
import com.puretv.twitch.core.stream.GqlPlaybackTokenException
import com.puretv.twitch.core.stream.StreamFailures

/**
 * What a VOD player says when a video will not open. Built on [StreamFailures],
 * but worded for a video rather than a channel: usher's "not found" means the
 * video was deleted, not that a channel is offline, and a restricted VOD is a
 * subscriber-only one, which the anonymous playback token can never open.
 */
object VodFailures {
    const val GONE = "This video is no longer available."
    const val SUBSCRIBERS_ONLY = "This video is for subscribers only."

    fun message(error: Throwable?): String {
        val failure = StreamFailures.classify(error)
        return when {
            failure.kind == FailureKind.RESTRICTED -> SUBSCRIBERS_ONLY
            failure.kind == FailureKind.OFFLINE -> GONE
            // GQL mints no token at all for a video that does not exist (any more).
            failure.kind == FailureKind.UNKNOWN && error.hasCause<GqlPlaybackTokenException>() -> GONE
            else -> failure.message()
        }
    }

    /** True when trying again cannot help, so the screen offers Back instead of Retry. */
    fun isPermanent(error: Throwable?): Boolean = message(error).let { it == GONE || it == SUBSCRIBERS_ONLY }

    private inline fun <reified T : Throwable> Throwable?.hasCause(): Boolean {
        var current = this
        var depth = 0
        while (current != null && depth < 8) {
            if (current is T) return true
            current = current.cause
            depth++
        }
        return false
    }
}
