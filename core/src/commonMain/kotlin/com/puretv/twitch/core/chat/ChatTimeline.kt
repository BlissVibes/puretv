package com.puretv.twitch.core.chat

import com.puretv.twitch.core.model.ChatEvent
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.model.MessagePart

/** How many chat rows every app keeps on screen. Older rows scroll away for good. */
const val MAX_CHAT_ROWS = 200

/**
 * The room's chat modes as the viewer last heard them. Twitch sends all of them
 * on join and then only the one that changed, so updates are merged in.
 */
data class ChatRoomModes(
    val slowSeconds: Int = 0,
    val emoteOnly: Boolean = false,
    /** -1 off, 0 any follower, N minutes of following. */
    val followersOnlyMinutes: Int = -1,
    val subsOnly: Boolean = false,
) {
    /** These modes with [update] applied; a null field in [update] keeps what we had. */
    fun merge(update: ChatEvent.RoomState): ChatRoomModes = ChatRoomModes(
        slowSeconds = update.slowModeSeconds ?: slowSeconds,
        emoteOnly = update.emoteOnly ?: emoteOnly,
        followersOnlyMinutes = update.followersOnlyMinutes ?: followersOnlyMinutes,
        subsOnly = update.subsOnly ?: subsOnly,
    )
}

/** What a chat panel shows: the rows, the rows that ping the viewer, and the room's modes. */
data class ChatTimelineState(
    val messages: List<ChatMessage> = emptyList(),
    /** Rows that @-mention the viewer, kept apart so they outlive the main buffer's churn. */
    val mentions: List<ChatMessage> = emptyList(),
    val modes: ChatRoomModes = ChatRoomModes(),
)

/** One step of a chat batch, already built (ids, emotes, mention flag) by [ChatTimeline.changesFor]. */
sealed class ChatChange {
    data class Add(val message: ChatMessage) : ChatChange()
    data class DeleteUser(val login: String) : ChatChange()
    data class DeleteMessage(val id: String) : ChatChange()
    data class Modes(val update: ChatEvent.RoomState) : ChatChange()
}

/**
 * The one set of chat rules every app follows (Windows, phone and TV used to keep
 * three hand-written copies that drifted apart): which events become rows, how
 * moderation tombstones them, which rows ping the viewer, and how room modes merge.
 *
 * It works in two halves because view models apply a batch inside
 * `MutableStateFlow.update {}`, whose lambda can run more than once:
 * - [changesFor] does everything with a side effect (system row ids, the clock,
 *   reading the viewer's login, emote decoration, handing over [ChatEvent.SelfState]).
 *   Call it once per batch, outside the update.
 * - [ChatTimelineState.applying] is pure. Run it inside the update; a retry gives
 *   the same answer, and it always starts from the latest rows, so a self echo or
 *   an emote re-map written elsewhere is never lost.
 *
 * Not thread-safe: call [changesFor] from the single chat collector.
 */
class ChatTimeline(
    private val channel: String,
    private val selfLogin: () -> String?,
    private val clock: () -> Long,
) {
    private var systemCounter = 0

    /**
     * Turns one batch of events into the changes to apply, in order. [decorate]
     * runs on every incoming viewer row (for example to resolve third-party
     * emotes); [onSelfState] receives the viewer's own name, colour and badges,
     * which never become a row.
     */
    fun changesFor(
        events: List<ChatEvent>,
        onSelfState: (ChatEvent.SelfState) -> Unit = {},
        decorate: (ChatMessage) -> ChatMessage = { it },
    ): List<ChatChange> {
        val out = ArrayList<ChatChange>(events.size)
        for (event in events) {
            when (event) {
                is ChatEvent.Message -> out += ChatChange.Add(incoming(event.message, decorate))
                is ChatEvent.SelfState -> onSelfState(event)
                // A moderator timed out or banned someone (target set) or cleared the
                // whole room (no target). Twitch drops that history, so the rows are
                // tombstoned rather than removed: "<message deleted>" keeps the
                // conversation around them readable, as Chatterino does.
                is ChatEvent.ClearChat -> out += event.targetUser
                    ?.let { ChatChange.DeleteUser(it) }
                    ?: ChatChange.Add(systemRow("Chat cleared by a moderator"))
                is ChatEvent.ClearMessage -> out += ChatChange.DeleteMessage(event.targetMessageId)
                // Subs, resubs, raids, gifts: Twitch writes the line for us. Announcements
                // and resubs also carry the viewer's own words in `raw`; show those under
                // it, as Twitch does. Newer notice types with no system text still show
                // their message rather than nothing.
                is ChatEvent.UserNotice -> {
                    event.systemMessage.ifBlank { null }?.let { out += ChatChange.Add(systemRow(it)) }
                    event.raw?.takeIf { it.message.isNotBlank() }?.let { out += ChatChange.Add(incoming(it, decorate)) }
                }
                // Twitch telling the viewer why a send did not go through (or a room mode
                // they ran into): show it instead of the message just vanishing.
                is ChatEvent.Notice -> out += ChatChange.Add(systemRow(event.text))
                is ChatEvent.RoomState -> out += ChatChange.Modes(event)
                is ChatEvent.ConnectionState -> {}
            }
        }
        return out
    }

    /** Mentions are viewer-relative, so they are worked out here where the login is known. */
    private fun incoming(message: ChatMessage, decorate: (ChatMessage) -> ChatMessage): ChatMessage =
        decorate(message).copy(mentionsSelf = ChatModeration.mentionsSelf(message.message, selfLogin()))

    /** A non-user row (sub and raid notices, "chat cleared"): no name, no badges. */
    private fun systemRow(text: String): ChatMessage = ChatMessage(
        // "sys-" never collides with Twitch's UUID ids or the "self-" echo ids (list keys).
        id = "sys-" + (systemCounter++).toString(),
        channel = channel,
        username = "",
        displayName = "",
        color = "",
        message = text,
        parsedParts = listOf(MessagePart.Text(text)),
        badges = emptyList(),
        timestamp = clock(),
        isSubscriber = false,
        isModerator = false,
        isBroadcaster = false,
        isSystem = true,
    )
}

/**
 * This state with a batch of [changes] applied in arrival order, so a deletion in
 * the same batch as the message it targets still finds it. The rows are copied
 * once and capped once per batch (not per message), and a list nothing touched
 * stays the same instance, so the UI does not recompose rows for a mode change or
 * a deletion that matched nothing. Pure: safe inside `MutableStateFlow.update {}`.
 */
fun ChatTimelineState.applying(changes: List<ChatChange>, max: Int = MAX_CHAT_ROWS): ChatTimelineState {
    if (changes.isEmpty()) return this
    val rows = ArrayList<ChatMessage>(messages.size + changes.size).apply { addAll(messages) }
    var rowsChanged = false
    // Mentions are rare, so their working copy is only made when something touches them.
    var pings: ArrayList<ChatMessage>? = null
    fun pings(): ArrayList<ChatMessage> = pings ?: ArrayList(mentions).also { pings = it }
    var newModes = modes

    for (change in changes) {
        when (change) {
            is ChatChange.Add -> {
                rows.add(change.message)
                rowsChanged = true
                if (change.message.mentionsSelf) pings().add(change.message)
            }
            is ChatChange.DeleteUser -> {
                if (ChatModeration.markUserDeletedInPlace(rows, change.login)) rowsChanged = true
                if (mentions.isNotEmpty() || pings != null) ChatModeration.markUserDeletedInPlace(pings(), change.login)
            }
            is ChatChange.DeleteMessage -> {
                if (ChatModeration.markMessageDeletedInPlace(rows, change.id)) rowsChanged = true
                if (mentions.isNotEmpty() || pings != null) ChatModeration.markMessageDeletedInPlace(pings(), change.id)
            }
            is ChatChange.Modes -> newModes = newModes.merge(change.update)
        }
    }

    val newRows = if (rowsChanged) rows.capped(max) else messages
    val newMentions = pings?.takeIf { it != mentions }?.capped(max) ?: mentions
    if (newRows === messages && newMentions === mentions && newModes == modes) return this
    return ChatTimelineState(messages = newRows, mentions = newMentions, modes = newModes)
}

/** Drops the oldest rows past [max], in place (no second copy). */
private fun ArrayList<ChatMessage>.capped(max: Int): ArrayList<ChatMessage> {
    if (size > max) subList(0, size - max).clear()
    return this
}
