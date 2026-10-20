package com.puretv.twitch.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatRoomModesTest {

    @Test
    fun anOpenRoomHasNoLabels() {
        assertTrue(ChatRoomModes().labels().isEmpty())
    }

    @Test
    fun everyModeHasAPlainLabelInAFixedOrder() {
        val modes = ChatRoomModes(slowSeconds = 30, emoteOnly = true, followersOnlyMinutes = 0, subsOnly = true)
        assertEquals(
            listOf("Slow mode: 30s", "Emote-only chat", "Followers-only chat", "Subscriber-only chat"),
            modes.labels(),
        )
    }

    @Test
    fun followersOnlySaysHowLongYouMustHaveFollowed() {
        assertEquals(listOf("Followers-only chat (10 min)"), ChatRoomModes(followersOnlyMinutes = 10).labels())
        assertEquals(listOf("Followers-only chat (1 hour)"), ChatRoomModes(followersOnlyMinutes = 60).labels())
        assertEquals(listOf("Followers-only chat (3 hours)"), ChatRoomModes(followersOnlyMinutes = 180).labels())
        assertEquals(listOf("Followers-only chat (1 day)"), ChatRoomModes(followersOnlyMinutes = 1_440).labels())
        assertEquals(listOf("Followers-only chat (7 days)"), ChatRoomModes(followersOnlyMinutes = 10_080).labels())
        assertEquals(listOf("Followers-only chat (90 min)"), ChatRoomModes(followersOnlyMinutes = 90).labels())
    }

    @Test
    fun longSlowModeReadsInMinutes() {
        assertEquals(listOf("Slow mode: 2 min"), ChatRoomModes(slowSeconds = 120).labels())
        assertEquals(listOf("Slow mode: 90s"), ChatRoomModes(slowSeconds = 90).labels())
    }
}
