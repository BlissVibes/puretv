package com.puretv.twitch.desktop.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where a chat message starts (Settings > Chat > Long messages). Items are the prefix
 * ("Name:") then words, all 10 high, with a gap of 1 between them on a 100-wide row.
 */
class ChatLinePlannerTest {
    private fun plan(widths: List<Int>, mode: LongMessageBreak, trailing: Int = 0) =
        ChatLinePlanner.plan(
            widths = widths.toIntArray(),
            heights = IntArray(widths.size) { 10 },
            maxWidth = 100,
            gap = 1,
            lineGap = 0,
            mode = mode,
            trailing = trailing,
        )

    // Name 40 wide. Inline: name + 29 + 29 on line 1, then 29 29 29 on line 2 = 2 lines.
    // On its own line: name, then 29 29 29 / 29 29 = 3 lines. So breaking costs a line.
    private val costsALine = listOf(40, 29, 29, 29, 29, 29)

    // Name 60 wide, three words of 49. Inline: name; 49 | 49 49 = ... name+49 does not fit
    // (60+1+49 = 110), so inline is already name / 49 49 / 49 = 3 lines, the same as breaking.
    private val freeBreak = listOf(60, 49, 49, 49)

    @Test fun aShortMessageStaysBesideTheNameInEveryMode() {
        for (mode in LongMessageBreak.entries) {
            val p = plan(listOf(40, 20, 20), mode)
            assertEquals(1, p.lines, "mode $mode")
            assertFalse(p.brokeBeforeBody, "mode $mode")
        }
    }

    @Test fun neverStartsALongMessageRightAfterTheName() {
        val p = plan(costsALine, LongMessageBreak.NEVER)
        assertFalse(p.brokeBeforeBody)
        assertEquals(2, p.lines)
        assertEquals(0, p.y[1]) // first word on the name's line
    }

    @Test fun alwaysPutsALongMessageUnderTheNameEvenWhenItCostsALine() {
        val p = plan(costsALine, LongMessageBreak.ALWAYS)
        assertTrue(p.brokeBeforeBody)
        assertEquals(3, p.lines)
        assertEquals(0, p.x[1])
    }

    @Test fun noExtraKeepsTheMessageBesideTheNameWhenBreakingWouldAddALine() {
        val p = plan(costsALine, LongMessageBreak.NO_EXTRA)
        assertFalse(p.brokeBeforeBody)
        assertEquals(2, p.lines)
    }

    @Test fun noExtraBreaksWhenItCostsNothing() {
        val p = plan(freeBreak, LongMessageBreak.NO_EXTRA)
        assertTrue(p.brokeBeforeBody)
        assertEquals(plan(freeBreak, LongMessageBreak.NEVER).lines, p.lines)
    }

    @Test fun anEmoteAfterAWrappedSentenceSitsBesideTheLastWord() {
        // Name, words that fill a line and a bit, then a 10-wide emote: it follows the last word.
        val p = plan(listOf(40, 29, 29, 29, 20, 10), LongMessageBreak.NEVER)
        assertEquals(p.y[4], p.y[5])
        assertEquals(p.x[4] + 20 + 1, p.x[5])
    }

    @Test fun theReplyArrowNeverChangesWhereTheMessageStarts() {
        // Inline the arrow would push to a third line; it must not flip the decision.
        val without = plan(freeBreak, LongMessageBreak.NO_EXTRA)
        val with = plan(freeBreak + 10, LongMessageBreak.NO_EXTRA, trailing = 1)
        assertEquals(without.brokeBeforeBody, with.brokeBeforeBody)
    }

    @Test fun oldSettingsFilesLoadTheDefault() {
        assertEquals(LongMessageBreak.NO_EXTRA, LongMessageBreak.parse(null))
        assertEquals(LongMessageBreak.NO_EXTRA, LongMessageBreak.parse("garbage"))
        assertEquals(LongMessageBreak.NEVER, LongMessageBreak.parse("never"))
    }

    @Test fun wordsSplitOnSpaces() {
        assertEquals(listOf("they", "meant"), chatWords("they  meant "))
    }
}
