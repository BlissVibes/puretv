package com.puretv.twitch.core.playback

import com.puretv.twitch.core.model.StreamInfo
import com.puretv.twitch.core.vod.ResumePolicy
import com.puretv.twitch.core.vod.WatchProgress

/** A channel the viewer watched recently, newest first in the list it comes in. */
data class RecentChannel(val login: String, val displayName: String)

/** One card of the TV "Continue watching" row. */
sealed interface ContinueItem {
    /** A recently watched channel; [stream] is set while it is live, which also gives the live thumbnail. */
    data class Channel(val login: String, val displayName: String, val stream: StreamInfo?) : ContinueItem

    /** A past broadcast worth resuming. */
    data class Vod(val progress: WatchProgress) : ContinueItem
}

/**
 * Builds the TV "Continue watching" row: recently watched channels with the live ones
 * first, then past broadcasts to resume, then channels that are offline right now.
 * Everything that is not live is kept to a short tail so the row stays about picking up
 * where you were, not a full history.
 */
object ContinueWatching {
    const val MAX_ITEMS = 15
    const val MAX_OFFLINE = 4

    /** How many of the most recent channels have their live status looked up. */
    const val LOOKUP_LIMIT = 12

    /**
     * The recent channels worth asking Twitch about: the newest [LOOKUP_LIMIT] that no
     * other list already told us were live. Keeps the extra call to a handful of logins,
     * or none.
     */
    fun loginsToCheck(recent: List<RecentChannel>, knownLive: Set<String>): List<String> {
        val known = knownLive.mapTo(HashSet()) { it.lowercase() }
        return recent.take(LOOKUP_LIMIT).map { it.login }.filter { it.lowercase() !in known }
    }

    /**
     * [live] maps a lowercase login to its stream (from any list that knows). [vods]
     * are all saved positions; only resumable ones (see [ResumePolicy]) are kept.
     */
    fun build(
        recent: List<RecentChannel>,
        vods: List<WatchProgress>,
        live: Map<String, StreamInfo>,
    ): List<ContinueItem> {
        val channels = recent.take(LOOKUP_LIMIT).map { ContinueItem.Channel(it.login, it.displayName, live[it.login.lowercase()]) }
        val liveFirst = channels.filter { it.stream != null }
        val offline = channels.filter { it.stream == null }.take(MAX_OFFLINE)
        val resumable = vods
            .filter { ResumePolicy.resumePositionMs(it) != null }
            .sortedByDescending { it.updatedAt }
            .map { ContinueItem.Vod(it) }
        return (liveFirst + resumable + offline).take(MAX_ITEMS)
    }
}
