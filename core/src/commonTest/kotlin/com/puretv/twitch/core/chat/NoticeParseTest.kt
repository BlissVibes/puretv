package com.puretv.twitch.core.chat

import com.puretv.twitch.core.model.ChatEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoticeParseTest {
    @Test
    fun aRejectedSendBecomesANoticeTheViewerCanRead() {
        val event = TwitchIrcParser.parse(
            "@msg-id=msg_subsonly :tmi.twitch.tv NOTICE #somechannel :This room is in subscribers-only mode.",
            "somechannel",
        )

        assertEquals(ChatEvent.Notice("msg_subsonly", "This room is in subscribers-only mode."), event)
    }

    @Test
    fun serverChatterWithoutTagsIsIgnored() {
        assertNull(TwitchIrcParser.parse(":tmi.twitch.tv NOTICE * :Login authentication failed", "somechannel"))
    }

    @Test
    fun anAnnouncementKeepsTheViewersWords() {
        val event = TwitchIrcParser.parse(
            "@badges=;display-name=Mod;id=abc;msg-id=announcement;system-msg=;tmi-sent-ts=1 " +
                ":mod!mod@mod.tmi.twitch.tv USERNOTICE #somechannel :Giveaway at the top of the hour",
            "somechannel",
        ) as ChatEvent.UserNotice

        assertEquals("Giveaway at the top of the hour", event.raw?.message)
    }
}
