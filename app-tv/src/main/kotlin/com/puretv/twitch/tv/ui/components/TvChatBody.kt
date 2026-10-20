package com.puretv.twitch.tv.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.model.MessagePart

/**
 * A chat line's body with emotes drawn inline as images, for the TV chat rail.
 * Ported from the phone app's `ChatPanel` builder (app-tv shares no UI code with
 * app-android, only `core`). Twitch's own emotes come tagged in
 * [ChatMessage.parsedParts]; third-party (7TV/BTTV/FFZ) ones are matched word by
 * word against [emotes]. Emotes are 1.6 em so they grow with the chat text size
 * and stay readable from the sofa.
 */
internal fun buildTvChatBody(
    message: ChatMessage,
    emotes: Map<String, String>,
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val inline = HashMap<String, InlineTextContent>()
    val text = buildAnnotatedString {
        message.parsedParts.forEach { part ->
            when (part) {
                is MessagePart.Text -> appendChatWords(part.content, emotes, inline)
                is MessagePart.TwitchEmote -> appendChatEmote("tw_${part.id}", part.name, twitchEmoteUrl(part.id), inline)
                is MessagePart.ThirdPartyEmote -> appendChatEmote("tp_${part.name}", part.name, part.url, inline)
            }
        }
    }
    return text to inline
}

private fun AnnotatedString.Builder.appendChatWords(
    content: String,
    emotes: Map<String, String>,
    inline: HashMap<String, InlineTextContent>,
) {
    val tokens = content.split(" ")
    tokens.forEachIndexed { i, token ->
        val url = if (token.isNotBlank()) emotes[token] else null
        if (url != null) appendChatEmote("tp_$token", token, url, inline) else append(token)
        if (i < tokens.lastIndex) append(" ")
    }
}

private fun AnnotatedString.Builder.appendChatEmote(
    id: String,
    name: String,
    url: String,
    inline: HashMap<String, InlineTextContent>,
) {
    appendInlineContent(id, name)
    if (!inline.containsKey(id)) {
        inline[id] = InlineTextContent(
            Placeholder(width = 1.6.em, height = 1.6.em, placeholderVerticalAlign = PlaceholderVerticalAlign.Center),
        ) {
            AsyncImage(
                model = url,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** Twitch first-party emote CDN URL (static, dark, 2x), built from the emote id. */
private fun twitchEmoteUrl(id: String): String =
    "https://static-cdn.jtvnw.net/emoticons/v2/$id/static/dark/2.0"
