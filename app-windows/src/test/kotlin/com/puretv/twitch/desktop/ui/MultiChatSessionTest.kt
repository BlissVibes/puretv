package com.puretv.twitch.desktop.ui

import com.puretv.twitch.core.model.ChatEvent
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.model.MessagePart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiChatSessionTest {

    private fun msg(channel: String) = ChatMessage(
        id = "1",
        channel = channel,
        username = "a",
        displayName = "a",
        color = "#fff",
        message = "hi",
        parsedParts = listOf(MessagePart.Text("hi")),
        badges = emptyList(),
        timestamp = 0L,
        isSubscriber = false,
        isModerator = false,
        isBroadcaster = false,
    )

    @Test
    fun modesAndDeletionsFromBeforeThisRoomConnectedAreDropped() {
        val batch = listOf(
            ChatEvent.RoomState(slowModeSeconds = 30),
            ChatEvent.ClearChat(targetUser = null, durationSeconds = null),
            ChatEvent.Notice("msg", "old room"),
            ChatEvent.ConnectionState(connected = true),
            ChatEvent.RoomState(emoteOnly = true),
        )
        val (mine, joined) = eventsForRoom(batch, "newroom", joined = false)
        assertTrue(joined)
        assertEquals(listOf(ChatEvent.ConnectionState(connected = true), ChatEvent.RoomState(emoteOnly = true)), mine)
    }

    @Test
    fun onceJoinedRoomEventsPassThrough() {
        val batch = listOf(ChatEvent.ClearMessage("m1"), ChatEvent.RoomState(subsOnly = true))
        val (mine, joined) = eventsForRoom(batch, "newroom", joined = true)
        assertTrue(joined)
        assertEquals(batch, mine)
    }

    @Test
    fun rowsAreStillMatchedByRoomName() {
        val batch = listOf(
            ChatEvent.Message(msg("oldroom")),
            ChatEvent.Message(msg("NewRoom")),
            ChatEvent.UserNotice("sub", msg("oldroom")),
        )
        val (mine, joined) = eventsForRoom(batch, "newroom", joined = false)
        assertTrue(joined)
        assertEquals(listOf(batch[1]), mine)
    }

    @Test
    fun aRowFromThisRoomOpensTheGate() {
        val batch = listOf(ChatEvent.Message(msg("newroom")), ChatEvent.RoomState(slowModeSeconds = 5))
        val (mine, joined) = eventsForRoom(batch, "newroom", joined = false)
        assertTrue(joined)
        assertEquals(batch, mine)
    }
}
