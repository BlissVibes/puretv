package com.puretv.twitch.core.notify

import com.puretv.twitch.core.model.StreamInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoLiveBannerQueueTest {
    private fun s(login: String, title: String = "") =
        StreamInfo(id = "id-$login", userId = "u-$login", userLogin = login, userName = login.replaceFirstChar { it.uppercase() }, title = title)

    @Test fun oneBannerPerStreamWithItsTitle() {
        val queue = GoLiveBannerQueue()
        queue.offer(listOf(s("alice", "Speedruns")), watchingLogin = null)
        val banner = queue.pending.value.single()
        assertEquals("alice", banner.key)
        assertEquals("Alice is live", banner.title)
        assertEquals("Speedruns", banner.body)
    }

    @Test fun watchedChannelGetsNoBanner() {
        val queue = GoLiveBannerQueue()
        queue.offer(listOf(s("alice")), watchingLogin = "alice")
        assertTrue(queue.pending.value.isEmpty())
    }

    @Test fun aChannelAlreadyWaitingIsNotQueuedTwice() {
        val queue = GoLiveBannerQueue()
        queue.offer(listOf(s("alice")), null)
        queue.offer(listOf(s("Alice"), s("bob")), null)
        assertEquals(listOf("alice", "bob"), queue.pending.value.map { it.key })
    }

    @Test fun manyAtOnceBecomeOneSummary() {
        val queue = GoLiveBannerQueue()
        queue.offer(listOf(s("a"), s("b"), s("c"), s("d"), s("e")), null)
        val banner = queue.pending.value.single()
        assertEquals("5 channels you follow went live", banner.title)
    }

    @Test fun overflowDropsOldestWaitingButNeverTheOneOnScreen() {
        val queue = GoLiveBannerQueue(maxPending = 3)
        queue.offer(listOf(s("a")), null)
        queue.offer(listOf(s("b")), null)
        queue.offer(listOf(s("c")), null)
        queue.offer(listOf(s("d")), null)
        assertEquals(listOf("a", "c", "d"), queue.pending.value.map { it.key })
    }

    @Test fun dismissShowsTheNextOne() {
        val queue = GoLiveBannerQueue()
        queue.offer(listOf(s("a"), s("b")), null)
        queue.dismiss(queue.pending.value.first())
        assertEquals(listOf("b"), queue.pending.value.map { it.key })
        queue.clear()
        assertTrue(queue.pending.value.isEmpty())
    }
}
