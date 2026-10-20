package com.puretv.twitch.core.chat

import com.puretv.twitch.core.model.Badge
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.model.MessagePart
import kotlin.test.Test
import kotlin.test.assertEquals

class BadgeChipsTest {

    // Only the moderator art resolves; the subscriber art did not load.
    private val index = BadgeIndex(mapOf("moderator" to mapOf("1" to ChatBadgeImage("mod2x", "Moderator"))))

    private fun msg(
        badges: List<Badge>,
        mod: Boolean = false,
        sub: Boolean = false,
        host: Boolean = false,
    ) = ChatMessage(
        id = "1",
        channel = "chan",
        username = "a",
        displayName = "a",
        color = "#fff",
        message = "hi",
        parsedParts = listOf(MessagePart.Text("hi")),
        badges = badges,
        timestamp = 0L,
        isSubscriber = sub,
        isModerator = mod,
        isBroadcaster = host,
    )

    @Test
    fun noArtGivesEveryRankAChip() {
        val m = msg(listOf(Badge("moderator", "1"), Badge("subscriber", "3")), mod = true, sub = true)
        assertEquals(listOf(RankChip.MOD, RankChip.SUB), missingRankChips(m, BadgeIndex.EMPTY))
    }

    @Test
    fun onlyTheUnresolvedRankKeepsItsChip() {
        val m = msg(listOf(Badge("moderator", "1"), Badge("subscriber", "3")), mod = true, sub = true)
        assertEquals(listOf(RankChip.SUB), missingRankChips(m, index))
    }

    @Test
    fun fullyResolvedNeedsNoChips() {
        val m = msg(listOf(Badge("moderator", "1")), mod = true)
        assertEquals(emptyList(), missingRankChips(m, index))
    }

    @Test
    fun streamerWinsOverModerator() {
        val m = msg(emptyList(), mod = true, host = true)
        assertEquals(listOf(RankChip.HOST), missingRankChips(m, index))
    }
}
