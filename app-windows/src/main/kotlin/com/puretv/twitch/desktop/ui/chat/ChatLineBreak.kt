package com.puretv.twitch.desktop.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where a long chat message starts, from Settings > Chat > Long messages. "Long" means the
 * message does not fit on the name's line.
 *
 *  - [ALWAYS]:   it starts on its own line under the name.
 *  - [NO_EXTRA]: it starts on its own line only when that costs no extra line, so the row
 *                is never taller for it; otherwise it starts right after the name.
 *  - [NEVER]:    it always starts right after the name, like Twitch.
 */
enum class LongMessageBreak(val key: String, val label: String) {
    ALWAYS("always", "Always"),
    NO_EXTRA("no_extra", "If no extra line"),
    NEVER("never", "Never");

    companion object {
        val DEFAULT = NO_EXTRA

        /** Lenient: anything unknown (or missing from an older settings file) is [DEFAULT]. */
        fun parse(key: String?): LongMessageBreak = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * Lays out one chat row as text does: a [prefix] item (timestamp, badges, name), then the
 * message's words and emotes, wrapping at the row's width. Kept free of Compose so the
 * break rules test with plain numbers.
 */
object ChatLinePlanner {

    /** One laid-out row: where each item goes (in item order) and the total height. */
    data class Plan(val x: IntArray, val y: IntArray, val height: Int, val lines: Int, val brokeBeforeBody: Boolean)

    /**
     * [widths]/[heights] are the items in order: index 0 is the prefix, then the body.
     * The last [trailing] items (the reply arrow) follow the body but never decide where
     * the body starts, so showing them does not make the message jump.
     */
    fun plan(
        widths: IntArray,
        heights: IntArray,
        maxWidth: Int,
        gap: Int,
        lineGap: Int,
        mode: LongMessageBreak,
        trailing: Int = 0,
    ): Plan {
        val n = widths.size
        val bodyEnd = (n - trailing).coerceAtLeast(1)
        val hasBody = bodyEnd > 1
        if (!hasBody || mode == LongMessageBreak.NEVER) return flow(widths, heights, maxWidth, gap, lineGap, breakBeforeBody = false)

        // Decide on the message alone (prefix and body, no trailing items).
        val w = widths.copyOfRange(0, bodyEnd)
        val h = heights.copyOfRange(0, bodyEnd)
        val inline = flow(w, h, maxWidth, gap, lineGap, breakBeforeBody = false)
        val long = inline.lines > 1
        val breakIt = when {
            !long -> false
            mode == LongMessageBreak.ALWAYS -> true
            else -> flow(w, h, maxWidth, gap, lineGap, breakBeforeBody = true).height <= inline.height
        }
        return flow(widths, heights, maxWidth, gap, lineGap, breakBeforeBody = breakIt)
    }

    private fun flow(
        widths: IntArray,
        heights: IntArray,
        maxWidth: Int,
        gap: Int,
        lineGap: Int,
        breakBeforeBody: Boolean,
    ): Plan {
        val n = widths.size
        val x = IntArray(n)
        val lineOf = IntArray(n)
        val lineHeights = ArrayList<Int>()
        var cx = 0
        var line = 0
        var lineHeight = 0
        for (i in 0 until n) {
            val startNewLine = cx > 0 && ((i == 1 && breakBeforeBody) || cx + gap + widths[i] > maxWidth)
            if (startNewLine) {
                lineHeights += lineHeight
                line++
                cx = 0
                lineHeight = 0
            }
            x[i] = if (cx == 0) 0 else cx + gap
            cx = x[i] + widths[i]
            lineOf[i] = line
            lineHeight = maxOf(lineHeight, heights[i])
        }
        lineHeights += lineHeight
        // Each item sits centred on its line, so an emote and the words beside it line up.
        val lineTops = IntArray(lineHeights.size)
        var top = 0
        for (l in lineHeights.indices) {
            lineTops[l] = top
            top += lineHeights[l] + if (l < lineHeights.lastIndex) lineGap else 0
        }
        val y = IntArray(n) { i -> lineTops[lineOf[i]] + (lineHeights[lineOf[i]] - heights[i]) / 2 }
        return Plan(x, y, top, lineHeights.size, breakBeforeBody && n > 1)
    }
}

/**
 * The chat row's layout (see [ChatLinePlanner]): the first child is the prefix, the rest
 * are the message's words and emotes, the last [trailing] children follow them.
 */
@Composable
fun ChatLineFlow(
    mode: LongMessageBreak,
    modifier: Modifier = Modifier,
    gap: Dp = 4.dp,
    lineGap: Dp = 2.dp,
    trailing: Int = 0,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        // Every item may be as wide as the row; a word longer than that wraps inside itself.
        val itemConstraints = Constraints(maxWidth = maxWidth)
        val placeables = measurables.map { it.measure(itemConstraints) }
        val plan = ChatLinePlanner.plan(
            widths = IntArray(placeables.size) { placeables[it].width },
            heights = IntArray(placeables.size) { placeables[it].height },
            maxWidth = maxWidth,
            gap = gap.roundToPx(),
            lineGap = lineGap.roundToPx(),
            mode = mode,
            trailing = trailing,
        )
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth
            else placeables.indices.maxOfOrNull { plan.x[it] + placeables[it].width } ?: 0
        layout(width.coerceAtLeast(constraints.minWidth), plan.height.coerceAtLeast(constraints.minHeight)) {
            placeables.forEachIndexed { i, p -> p.place(plan.x[i], plan.y[i]) }
        }
    }
}

/** Splits message text into words for [ChatLineFlow], which puts the spaces back as gaps. */
fun chatWords(text: String): List<String> = text.split(' ').filter { it.isNotEmpty() }

/**
 * Which chat message is selected, so only its reply arrow shows. One per chat list:
 * selecting another message moves the arrow there, selecting the same one again hides it.
 */
@Stable
class ChatSelection {
    var selectedId by mutableStateOf<String?>(null)
        private set

    fun toggle(id: String) {
        selectedId = if (selectedId == id) null else id
    }

    fun clear() {
        selectedId = null
    }
}

/** Provided by the chat list; null outside one, where rows keep their own selection. */
val LocalChatSelection = staticCompositionLocalOf<ChatSelection?> { null }
