package com.puretv.twitch.core.chat

import com.puretv.twitch.core.model.ChatEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomStateParseTest {
    private fun parse(line: String) = TwitchIrcParser.parse(line, "chan") as ChatEvent.RoomState

    @Test
    fun fullJoinStateParsesAllModes() {
        val e = parse("@emote-only=0;followers-only=10;r9k=0;room-id=1;slow=30;subs-only=1 :tmi.twitch.tv ROOMSTATE #chan")
        assertEquals(30, e.slowModeSeconds)
        assertEquals(false, e.emoteOnly)
        assertEquals(10, e.followersOnlyMinutes)
        assertEquals(true, e.subsOnly)
    }

    @Test
    fun partialUpdateLeavesOtherModesNull() {
        val e = parse("@room-id=1;slow=0 :tmi.twitch.tv ROOMSTATE #chan")
        assertEquals(0, e.slowModeSeconds)
        assertNull(e.emoteOnly)
        assertNull(e.followersOnlyMinutes)
        assertNull(e.subsOnly)
    }

    @Test
    fun followersOnlyOffIsMinusOne() {
        val e = parse("@followers-only=-1;room-id=1 :tmi.twitch.tv ROOMSTATE #chan")
        assertEquals(-1, e.followersOnlyMinutes)
        assertNull(e.slowModeSeconds)
    }

    @Test
    fun emoteOnlyOnByItself() {
        val e = parse("@emote-only=1;room-id=1 :tmi.twitch.tv ROOMSTATE #chan")
        assertEquals(true, e.emoteOnly)
        assertNull(e.subsOnly)
    }
}
