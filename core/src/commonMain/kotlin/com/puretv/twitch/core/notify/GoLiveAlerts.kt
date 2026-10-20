package com.puretv.twitch.core.notify

import com.puretv.twitch.core.model.StreamInfo

/** Rules the phone notifications and the TV banner share about what to show. */
object GoLiveAlerts {
    /** Up to this many streams from one poll get an alert each; more become one summary. */
    const val MAX_SEPARATE = 3

    /**
     * [streams] minus the channel the viewer is watching right now: an alert that
     * their own stream just went live is noise.
     */
    fun exceptWatching(streams: List<StreamInfo>, watchingLogin: String?): List<StreamInfo> {
        val watching = watchingLogin?.trim()?.takeIf { it.isNotEmpty() } ?: return streams
        return streams.filterNot { it.userLogin.equals(watching, ignoreCase = true) }
    }
}
