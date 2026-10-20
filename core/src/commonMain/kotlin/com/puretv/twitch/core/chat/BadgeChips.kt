package com.puretv.twitch.core.chat

import com.puretv.twitch.core.model.ChatMessage

/** The ranks that get a text chip when their badge art is not available. */
enum class RankChip { HOST, MOD, SUB }

/**
 * The text chips a row still needs next to the badge images that did resolve:
 * one for each of the sender's ranks (streamer or moderator, and subscriber)
 * whose own badge did not come back from [badges]. With no art at all that is
 * every rank, so a moderator or the streamer still stands out while art loads.
 */
fun missingRankChips(message: ChatMessage, badges: BadgeIndex): List<RankChip> {
    fun shown(vararg setIds: String): Boolean =
        message.badges.any { it.setId in setIds && badges.resolve(it.setId, it.version) != null }
    return buildList {
        if (message.isBroadcaster) {
            if (!shown("broadcaster")) add(RankChip.HOST)
        } else if (message.isModerator) {
            if (!shown("moderator")) add(RankChip.MOD)
        }
        if (message.isSubscriber && !shown("subscriber", "founder")) add(RankChip.SUB)
    }
}
