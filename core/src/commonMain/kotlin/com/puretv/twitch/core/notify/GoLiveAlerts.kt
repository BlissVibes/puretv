package com.puretv.twitch.core.notify

import com.puretv.twitch.core.model.AppSettings
import com.puretv.twitch.core.model.StreamInfo

/** Rules the phone notifications and the TV banner share about what to show. */
object GoLiveAlerts {
    /** Up to this many streams from one poll get an alert each; more become one summary. */
    const val MAX_SEPARATE = 3

    /** True when the bell is on for [login] in the opted-in list [channels]. */
    fun isOn(channels: Set<String>, login: String): Boolean {
        val key = login.trim()
        return key.isNotEmpty() && channels.any { it.equals(key, ignoreCase = true) }
    }

    /**
     * [settings] after tapping the bell for [login]. Turning a bell on also switches the
     * global toggle on, because a bell that never rings is worse than no bell.
     */
    fun bellTapped(settings: AppSettings, login: String): AppSettings {
        val channels = toggled(settings.notifyChannels, login)
        val added = channels.size > settings.notifyChannels.size
        return settings.copy(notifyChannels = channels, liveNotifications = settings.liveNotifications || added)
    }

    /** [channels] with the bell for [login] flipped; logins are kept lowercase. */
    fun toggled(channels: Set<String>, login: String): Set<String> {
        val key = login.trim().lowercase()
        if (key.isEmpty()) return channels
        val lower = channels.mapTo(LinkedHashSet()) { it.lowercase() }
        return if (key in lower) lower - key else lower + key
    }

    /**
     * [streams] minus the channel the viewer is watching right now: an alert that
     * their own stream just went live is noise.
     */
    fun exceptWatching(streams: List<StreamInfo>, watchingLogin: String?): List<StreamInfo> {
        val watching = watchingLogin?.trim()?.takeIf { it.isNotEmpty() } ?: return streams
        return streams.filterNot { it.userLogin.equals(watching, ignoreCase = true) }
    }
}
