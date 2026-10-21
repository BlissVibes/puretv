package com.puretv.twitch.core.notify

import com.puretv.twitch.core.model.StreamInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoLiveTextTest {
    private fun s(name: String, title: String = "", game: String = "") =
        StreamInfo(id = name, userId = name, userLogin = name.lowercase(), userName = name, title = title, gameName = game)

    @Test fun oneStreamUsesItsNameAndTitle() {
        val text = GoLiveText.forBatch(listOf(s("Alice", title = "  Speedruns all day ")))
        assertEquals("Alice is live", text.title)
        assertEquals("Speedruns all day", text.body)
    }

    @Test fun anUntitledStreamFallsBackToItsCategory() {
        assertEquals("Playing Chess", GoLiveText.forBatch(listOf(s("Bob", game = "Chess"))).body)
        assertEquals("Streaming now", GoLiveText.forBatch(listOf(s("Bob"))).body)
    }

    @Test fun longTitlesAreCut() {
        val body = GoLiveText.forBatch(listOf(s("Alice", title = "x".repeat(200)))).body
        assertTrue(body.length <= GoLiveText.MAX_BODY)
        assertTrue(body.endsWith("..."))
    }

    @Test fun twoOrThreeAreNamedTogether() {
        assertEquals("Alice and Bob are live", GoLiveText.forBatch(listOf(s("Alice"), s("Bob"))).title)
        assertEquals("Alice, Bob and Cy are live", GoLiveText.forBatch(listOf(s("Alice"), s("Bob"), s("Cy"))).title)
    }

    @Test fun manyAreSummarised() {
        val text = GoLiveText.forBatch(listOf(s("A"), s("B"), s("C"), s("D"), s("E")))
        assertEquals("5 channels went live", text.title)
        assertEquals("A, B, C and 2 more", text.body)
    }

    @Test fun aBlankDisplayNameFallsBackToTheLogin() {
        val stream = StreamInfo(id = "1", userId = "1", userLogin = "quiet", userName = " ")
        assertEquals("quiet is live", GoLiveText.forBatch(listOf(stream)).title)
    }
}
