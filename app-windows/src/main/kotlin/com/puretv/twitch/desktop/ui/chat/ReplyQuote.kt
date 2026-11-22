package com.puretv.twitch.desktop.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.puretv.twitch.desktop.ui.theme.PureTvTheme

/** The words of the line above a reply. */
object ReplyQuote {
    /**
     * "replying to @name: what they said", on one line. Twitch sends the quoted message with
     * the reply; when it did not (or sent it empty) the line is only "replying to @name".
     */
    fun line(parentName: String, parentBody: String?): String {
        val quote = oneLine(parentBody)
        return if (quote.isEmpty()) "replying to @$parentName" else "replying to @$parentName: $quote"
    }

    /** [text] with every run of spaces and line breaks as one space, and none at the ends. */
    fun oneLine(text: String?): String = text?.trim()?.replace(WHITESPACE, " ").orEmpty()

    private val WHITESPACE = Regex("\\s+")
}

/**
 * The line above a reply: who it answers and the start of what they said, cut to one line.
 * Resting the pointer on it shows the whole message.
 *
 * The tooltip opens under the line and is no wider than it, so it stays inside the chat
 * column: the video beside chat is a native surface that paints over any popup that
 * strays onto it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReplyContextLine(parentName: String, parentBody: String?, modifier: Modifier = Modifier) {
    val c = PureTvTheme.colors
    val line = remember(parentName, parentBody) { ReplyQuote.line(parentName, parentBody) }
    val quote = remember(parentBody) { parentBody?.trim().orEmpty() }
    val header: @Composable () -> Unit = {
        Text(
            line,
            style = MaterialTheme.typography.labelSmall,
            color = c.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (quote.isEmpty()) {
        Column(modifier) { header() }
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widest = maxWidth
        TooltipArea(
            tooltip = {
                Column(
                    Modifier
                        .widthIn(max = widest)
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surfaceHighest)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text("@$parentName", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    Text(quote, style = MaterialTheme.typography.bodySmall, color = c.onSurface)
                }
            },
            delayMillis = 250,
            tooltipPlacement = TooltipPlacement.ComponentRect(
                anchor = Alignment.BottomStart,
                alignment = Alignment.BottomEnd,
                offset = DpOffset(0.dp, 2.dp),
            ),
        ) {
            header()
        }
    }
}
