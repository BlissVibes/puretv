package com.puretv.twitch.core.chat

import com.puretv.twitch.core.emotes.ResolvedEmote
import com.puretv.twitch.core.emotes.applyThirdPartyEmotes
import com.puretv.twitch.core.model.Badge
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.model.MessagePart

/**
 * Builds an optimistic local echo of the user's OWN outgoing chat message.
 *
 * Twitch IRC never echoes your own PRIVMSG back to you (it has no IRCv3
 * echo-message capability). Even with twitch.tv/commands you only get a
 * USERSTATE confirmation, never the message text. So the client must render
 * your message itself or you never see what you typed. Identity (display name /
 * color / badges) comes from the USERSTATE / GLOBALUSERSTATE the server sends
 * after auth + join.
 *
 * Pass [text] through [outgoingChatBody] first so the echo shows exactly what
 * went on the wire. [emoteIndex] should include first-party Twitch emotes as
 * well as third-party ones: your own message never carries an `emotes=` tag, so
 * a typed Kappa or sub emote can only be resolved here by name.
 */
fun buildSelfEcho(
    id: String,
    login: String,
    displayName: String?,
    color: String?,
    badges: List<Badge>,
    channel: String,
    text: String,
    timestamp: Long,
    replyParent: ChatMessage? = null,
    emoteIndex: Map<String, ResolvedEmote> = emptyMap(),
): ChatMessage = ChatMessage(
    id = id,
    channel = channel,
    username = login,
    displayName = displayName?.ifBlank { null } ?: login,
    color = color?.ifBlank { null } ?: "#9B5DE5",
    message = text,
    parsedParts = applyThirdPartyEmotes(listOf(MessagePart.Text(text)), emoteIndex),
    badges = badges,
    timestamp = timestamp,
    isSubscriber = badges.any { it.setId == "subscriber" },
    isModerator = badges.any { it.setId == "moderator" },
    isBroadcaster = badges.any { it.setId == "broadcaster" },
    replyParentDisplayName = replyParent?.displayName,
    replyParentBody = replyParent?.message,
)
