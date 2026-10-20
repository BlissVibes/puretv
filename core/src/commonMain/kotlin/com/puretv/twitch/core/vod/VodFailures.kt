package com.puretv.twitch.core.vod

import com.puretv.twitch.core.stream.FailureKind
import com.puretv.twitch.core.stream.GqlPlaybackTokenException
import com.puretv.twitch.core.stream.GqlVideoMissingException
import com.puretv.twitch.core.stream.StreamFailures

/**
 * What a VOD player says when a video will not open. Built on [StreamFailures],
 * but worded for a video rather than a channel: usher's "not found" means the
 * video was deleted, not that a channel is offline, and a restricted VOD is a
 * subscriber-only one, which the anonymous playback token can never open.
 */
object VodFailures {
    const val GONE = "This video is no longer available."
    const val COULD_NOT_LOAD = "Couldn't load this video. Try again."
    const val SUBSCRIBERS_ONLY = "This video is for subscribers only."

    fun message(error: Throwable?): String {
        val failure = StreamFailures.classify(error)
        return when {
            failure.kind == FailureKind.RESTRICTED -> SUBSCRIBERS_ONLY
            failure.kind == FailureKind.OFFLINE -> GONE
            // GQL mints no token at all for a video that does not exist (any more), and says so
            // cleanly. A missing token WITH errors (integrity check, rate limit) is only a blip.
            failure.kind == FailureKind.UNKNOWN && error.hasCause<GqlVideoMissingException>() -> GONE
            failure.kind == FailureKind.UNKNOWN && error.hasCause<GqlPlaybackTokenException>() -> COULD_NOT_LOAD
            else -> failure.message()
        }
    }

    /**
     * True only when Twitch has said the video does not exist (usher 404 / not found, or a clean
     * GQL answer with no video). Safe to forget the viewer's saved place for it; a transient
     * failure never counts, so nothing is deleted on a blip.
     */
    fun isConfirmedGone(error: Throwable?): Boolean = message(error) == GONE

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
