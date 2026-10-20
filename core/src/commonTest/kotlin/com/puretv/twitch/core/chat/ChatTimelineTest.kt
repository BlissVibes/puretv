package com.puretv.twitch.core.chat

import com.puretv.twitch.core.model.Badge
import com.puretv.twitch.core.model.ChatEvent
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.model.MessagePart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatTimelineTest {

    private var self: String? = "bob"
    private val timeline = ChatTimeline(channel = "chan", selfLogin = { self }, clock = { 42L })

    private fun msg(id: String, login: String = "alice", body: String = "hello") = ChatMessage(
        id = id,
        channel = "chan",
        username = login,
        displayName = login,
        color = "#fff",
        message = body,
        parsedParts = listOf(MessagePart.Text(body)),
        badges = emptyList(),
        timestamp = 0L,
        isSubscriber = false,
        isModerator = false,
        isBroadcaster = false,
    )

    /** Both phases, the way a view model runs them: prepare once, then apply. */
    private fun ChatTimelineState.reduce(vararg events: ChatEvent, max: Int = 200): ChatTimelineState =
        applying(timeline.changesFor(events.toList()), max)

    @Test
    fun messageAppendsAndCapsOncePerBatch() {
        val full = ChatTimelineState(messages = (1..200).map { msg("m$it") })
        val after = full.reduce(ChatEvent.Message(msg("new1")), ChatEvent.Message(msg("new2")))
        assertEquals(200, after.messages.size)
        assertEquals("new2", after.messages.last().id)
        assertEquals("new1", after.messages[198].id)
        assertEquals("m3", after.messages.first().id)
    }

    @Test
    fun aMessageMentioningTheViewerAlsoLandsInMentions() {
        val after = ChatTimelineState().reduce(
            ChatEvent.Message(msg("1", body = "hi @Bob")),
            ChatEvent.Message(msg("2", body = "hi @bobby")),
        )
        assertTrue(after.messages[0].mentionsSelf)
        assertFalse(after.messages[1].mentionsSelf)
        assertEquals(listOf("1"), after.mentions.map { it.id })
    }

    @Test
    fun mentionsUseTheLoginKnownWhenTheBatchIsPrepared() {
        self = null
        val after = ChatTimelineState().reduce(ChatEvent.Message(msg("1", body = "@bob")))
        assertFalse(after.messages.single().mentionsSelf)
        assertTrue(after.mentions.isEmpty())
    }

    @Test
    fun decorateRunsOnIncomingRows() {
        val changes = timeline.changesFor(listOf(ChatEvent.Message(msg("1")))) { it.copy(color = "#000") }
        val after = ChatTimelineState().applying(changes)
        assertEquals("#000", after.messages.single().color)
    }

    @Test
    fun clearChatWithATargetTombstonesThatUser() {
        val before = ChatTimelineState(messages = listOf(msg("1", "troll"), msg("2", "alice"), msg("3", "Troll")))
        val after = before.reduce(ChatEvent.ClearChat(targetUser = "troll", durationSeconds = 600))
        assertEquals(listOf(true, false, true), after.messages.map { it.deleted })
    }

    @Test
    fun clearChatWithoutATargetAddsASystemRow() {
        val after = ChatTimelineState().reduce(ChatEvent.ClearChat(targetUser = null, durationSeconds = null))
        val row = after.messages.single()
        assertTrue(row.isSystem)
        assertEquals("Chat cleared by a moderator", row.message)
        assertEquals(42L, row.timestamp)
    }

    @Test
    fun clearChatWithoutATargetLeavesExistingRowsUntouched() {
        val before = ChatTimelineState(messages = listOf(msg("1", "alice"), msg("2", "troll")))
        val after = before.reduce(ChatEvent.ClearChat(targetUser = null, durationSeconds = null))
        assertEquals(listOf(false, false, false), after.messages.map { it.deleted })
        assertEquals(3, after.messages.size)
        assertTrue(after.messages.last().isSystem)
    }

    @Test
    fun clearMessageTombstonesById() {
        val before = ChatTimelineState(messages = listOf(msg("1"), msg("2")))
        val after = before.reduce(ChatEvent.ClearMessage("2"))
        assertEquals(listOf(false, true), after.messages.map { it.deleted })
    }

    @Test
    fun aDeletionInTheSameBatchFindsTheMessageItTargets() {
        val after = ChatTimelineState().reduce(
            ChatEvent.Message(msg("1", "troll")),
            ChatEvent.ClearChat(targetUser = "troll", durationSeconds = 1),
            ChatEvent.Message(msg("2", "troll")),
        )
        assertEquals(listOf(true, false), after.messages.map { it.deleted })
    }

    @Test
    fun deletionsAlsoReachTheMentionsBuffer() {
        val before = ChatTimelineState().reduce(ChatEvent.Message(msg("1", "troll", "@bob hi")))
        val after = before.reduce(ChatEvent.ClearMessage("1"))
        assertTrue(after.mentions.single().deleted)
    }

    @Test
    fun aDeletionThatMatchesNothingKeepsTheSameLists() {
        val before = ChatTimelineState(messages = listOf(msg("1")), mentions = listOf(msg("1")))
        val after = before.reduce(ChatEvent.ClearMessage("nope"), ChatEvent.ClearChat("nobody", null))
        assertSame(before.messages, after.messages)
        assertSame(before.mentions, after.mentions)
    }

    @Test
    fun userNoticeShowsTheSystemLineAndTheViewersWords() {
        val after = ChatTimelineState().reduce(
            ChatEvent.UserNotice("alice subscribed for 3 months!", msg("r1", body = "love this stream @bob")),
        )
        assertEquals(2, after.messages.size)
        assertTrue(after.messages[0].isSystem)
        assertEquals("alice subscribed for 3 months!", after.messages[0].message)
        assertEquals("r1", after.messages[1].id)
        assertTrue(after.messages[1].mentionsSelf)
        assertEquals(listOf("r1"), after.mentions.map { it.id })
    }

    @Test
    fun userNoticeWithABlankSystemLineStillShowsAnAnnouncement() {
        val after = ChatTimelineState().reduce(ChatEvent.UserNotice("", msg("r1", body = "Giveaway soon")))
        assertEquals(listOf("r1"), after.messages.map { it.id })
        assertFalse(after.messages.single().isSystem)
    }

    @Test
    fun userNoticeWithNothingToShowIsIgnored() {
        val before = ChatTimelineState()
        val after = before.reduce(ChatEvent.UserNotice("", msg("r1", body = "")))
        assertSame(before, after)
    }

    @Test
    fun aNoticeBecomesASystemRow() {
        val after = ChatTimelineState().reduce(ChatEvent.Notice("msg_subsonly", "This room is in subscribers-only mode."))
        assertTrue(after.messages.single().isSystem)
        assertEquals("This room is in subscribers-only mode.", after.messages.single().message)
    }

    @Test
    fun systemRowsGetDistinctIds() {
        val after = ChatTimelineState().reduce(
            ChatEvent.Notice("a", "one"),
            ChatEvent.Notice("b", "two"),
        )
        assertEquals(2, after.messages.map { it.id }.toSet().size)
    }

    @Test
    fun roomStateMergesPartialUpdates() {
        val joined = ChatTimelineState().reduce(
            ChatEvent.RoomState(slowModeSeconds = 0, emoteOnly = true, followersOnlyMinutes = -1, subsOnly = false),
        )
        val after = joined.reduce(ChatEvent.RoomState(slowModeSeconds = 30))
        assertEquals(ChatRoomModes(slowSeconds = 30, emoteOnly = true, followersOnlyMinutes = -1, subsOnly = false), after.modes)
        assertSame(joined.messages, after.messages)
    }

    @Test
    fun connectionAndSelfStateReturnTheSameState() {
        val before = ChatTimelineState(messages = listOf(msg("1")))
        var captured: ChatEvent.SelfState? = null
        val selfState = ChatEvent.SelfState("Bob", "#f00", listOf(Badge("subscriber", "3")))
        val changes = timeline.changesFor(
            listOf(ChatEvent.ConnectionState(connected = true), selfState),
            onSelfState = { captured = it },
        )
        assertSame(before, before.applying(changes))
        assertEquals(selfState, captured)
    }

    @Test
    fun aLocalEchoIsNeverAMention() {
        val echo = msg("self-0", "bob", "@bob talking to myself")
        val after = ChatTimelineState().applying(listOf(ChatChange.Add(echo)))
        assertEquals(listOf("self-0"), after.messages.map { it.id })
        assertTrue(after.mentions.isEmpty())
    }
}
