package com.puretv.twitch.desktop.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ReplyQuoteTest {
    @Test
    fun theLineNamesWhoAndQuotesWhatTheySaid() {
        assertEquals("replying to @Ana: did you see that", ReplyQuote.line("Ana", "did you see that"))
    }

    @Test
    fun withoutAQuoteTheLineOnlyNamesWho() {
        assertEquals("replying to @Ana", ReplyQuote.line("Ana", null))
        assertEquals("replying to @Ana", ReplyQuote.line("Ana", ""))
        assertEquals("replying to @Ana", ReplyQuote.line("Ana", "   "))
    }

    @Test
    fun aQuoteIsPutOnOneLine() {
        assertEquals("replying to @Ana: one two three", ReplyQuote.line("Ana", "  one\ttwo \n three "))
    }
}
