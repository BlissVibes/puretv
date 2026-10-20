package com.puretv.twitch.core.vod

import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.stream.CommentBatch
import com.puretv.twitch.core.stream.ReplayComment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VodChatReplayControllerTest {

    private fun c(id: String, off: Int) = ReplayComment(
        offsetSeconds = off,
        message = ChatMessage(id, "", "u", "u", "", "m", emptyList(), emptyList(), 0, false, false, false),
    )

    private class Fake {
        val requested = mutableListOf<Int>()
        var failNext = false
        var pages: (Int) -> CommentBatch = { CommentBatch(emptyList(), hasNextPage = false) }
        var gate: CompletableDeferred<Unit>? = null
        suspend fun fetch(offset: Int): CommentBatch {
            requested += offset
            gate?.await()
            if (failNext) { failNext = false; error("network") }
            return pages(offset)
        }
    }

    // runCurrent, not advanceUntilIdle: the controller lives in backgroundScope, whose work
    // advanceUntilIdle does not wait for.
    private fun TestScope.controller(fake: Fake) =
        VodChatReplayController(backgroundScope, fake::fetch) { emptyMap() }

    private fun VodChatReplayController.ids() = messages.value.map { it.id }

    @Test
    fun forwardPlaybackEmitsDueComments() = runTest {
        val fake = Fake().apply { pages = { CommentBatch(listOf(c("a", 1), c("b", 5), c("c", 30)), hasNextPage = false) } }
        val ctl = controller(fake)
        runCurrent()
        ctl.onPosition(0); runCurrent()
        assertEquals(listOf<String>(), ctl.ids())
        ctl.onPosition(6); runCurrent()
        assertEquals(listOf("a", "b"), ctl.ids())
        ctl.onPosition(31); runCurrent()
        assertEquals(listOf("a", "b", "c"), ctl.ids())
        assertEquals(listOf(0), fake.requested)
    }

    @Test
    fun prefetchesTwentySecondsBeforeTheBufferEnds() = runTest {
        val fake = Fake().apply {
            pages = { offset ->
                if (offset == 0) CommentBatch(listOf(c("a", 5), c("b", 60)), hasNextPage = true)
                else CommentBatch(listOf(c("c", 120)), hasNextPage = false)
            }
        }
        val ctl = controller(fake)
        runCurrent()
        ctl.onPosition(30); runCurrent()
        assertEquals(listOf(0), fake.requested)
        ctl.onPosition(41); runCurrent()
        assertEquals(listOf(0, 60), fake.requested)
        ctl.onPosition(121); runCurrent()
        assertEquals(listOf("a", "b", "c"), ctl.ids())
    }

    @Test
    fun backwardSeekOverThreeSecondsResetsAndReloads() = runTest {
        val fake = Fake().apply {
            pages = { offset -> CommentBatch(listOf(c("x$offset", offset)), hasNextPage = true) }
        }
        val ctl = controller(fake)
        runCurrent()
        ctl.onPosition(100); runCurrent()
        ctl.onPosition(10); runCurrent()
        assertEquals(10, fake.requested.last())
        assertEquals(listOf("x10"), ctl.ids())
    }

    @Test
    fun smallBackwardStepDoesNotReload() = runTest {
        val fake = Fake().apply { pages = { CommentBatch(listOf(c("a", 1)), hasNextPage = false) } }
        val ctl = controller(fake)
        runCurrent()
        ctl.onPosition(10); runCurrent()
        ctl.onPosition(8); runCurrent()
        assertEquals(listOf(0), fake.requested)
    }

    @Test
    fun aLoadSupersededByABackwardSeekIsDiscarded() = runTest {
        val fake = Fake()
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        fake.pages = { offset -> CommentBatch(listOf(c("old$offset", offset)), hasNextPage = true) }
        val ctl = controller(fake)
        runCurrent()                    // the first load (offset 0) is parked on the gate
        ctl.onPosition(100); runCurrent()
        ctl.onPosition(10); runCurrent() // backward seek: voids the parked load, requests 10
        gate.complete(Unit); runCurrent()
        assertEquals(10, fake.requested.last())
        // Only the reload's window may show; nothing from the voided offset 0 load.
        assertEquals(listOf("old10"), ctl.ids())
    }

    @Test
    fun aFailedFetchSetsTheErrorAndALaterSuccessClearsIt() = runTest {
        val fake = Fake().apply {
            failNext = true
            pages = { CommentBatch(listOf(c("a", 1)), hasNextPage = true) }
        }
        val ctl = controller(fake)
        runCurrent()
        assertEquals(VodChatReplayController.ERROR_TEXT, ctl.error.value)
        ctl.onPosition(5); runCurrent()   // buffer still empty, so it asks again
        assertNull(ctl.error.value)
        assertEquals(listOf("a"), ctl.ids())
    }
}
