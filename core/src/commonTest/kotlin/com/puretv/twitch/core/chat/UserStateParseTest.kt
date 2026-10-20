package com.puretv.twitch.core.chat

import com.puretv.twitch.core.model.ChatEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UserStateParseTest {
    @Test fun userStateBecomesSelfStateWithChannelBadges() {
        val line = "@badges=subscriber/12;color=#FF0000;display-name=Dhawal :tmi.twitch.tv USERSTATE #shroud"
        val event = TwitchIrcParser.parse(line, "shroud")
        assertIs<ChatEvent.SelfState>(event)
        assertEquals("Dhawal", event.displayName)
        assertEquals("#FF0000", event.color)
        assertTrue(event.badges.any { it.setId == "subscriber" })
    }

    @Test fun userStateWithoutColorFallsBackToDefault() {
        val line = "@badges=;color=;display-name=Dhawal :tmi.twitch.tv USERSTATE #shroud"
        val event = TwitchIrcParser.parse(line, "shroud")
        assertIs<ChatEvent.SelfState>(event)
        assertEquals("#9B5DE5", event.color)
    }

    @Test fun globalUserStateCarriesTheViewersUserId() {
        val line = "@badges=;color=#00FF00;display-name=Dhawal;user-id=123456 :tmi.twitch.tv GLOBALUSERSTATE"
        val event = TwitchIrcParser.parse(line, "shroud")
        assertIs<ChatEvent.SelfState>(event)
        assertEquals("123456", event.userId)
    }

    @Test fun userStateHasNoUserId() {
        // Twitch only sends user-id on GLOBALUSERSTATE; USERSTATE must not invent one.
        val line = "@badges=;color=#00FF00;display-name=Dhawal :tmi.twitch.tv USERSTATE #shroud"
        val event = TwitchIrcParser.parse(line, "shroud")
        assertIs<ChatEvent.SelfState>(event)
        assertEquals("", event.userId)
    }

    @Test fun privmsgCarriesTheAuthorsUserId() {
        val line = "@badges=;color=#FF0000;display-name=Alice;id=m1;tmi-sent-ts=1;user-id=987 " +
            ":alice!alice@alice.tmi.twitch.tv PRIVMSG #shroud :hello"
        val event = TwitchIrcParser.parse(line, "shroud")
        assertIs<ChatEvent.Message>(event)
        assertEquals("987", event.message.userId)
    }

    @Test fun privmsgWithoutUserIdLeavesItBlank() {
        val line = "@display-name=Alice;id=m2 :alice!alice@alice.tmi.twitch.tv PRIVMSG #shroud :hi"
        val event = TwitchIrcParser.parse(line, "shroud")
        assertIs<ChatEvent.Message>(event)
        assertEquals("", event.message.userId)
    }
}
