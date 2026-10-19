package com.puretv.twitch.core.chat

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatBatchingTest {

    @Test
    fun aQuietChatSendsEachMessageAtOnce() = runTest {
        val times = mutableListOf<Long>()
        val batches = flow {
            emit(1)
            delay(1_000)
            emit(2)
        }.batchedEvery(100).let { batched ->
            val out = mutableListOf<List<Int>>()
            batched.collect { out += it; times += currentTime }
            out
        }
        assertEquals(listOf(listOf(1), listOf(2)), batches)
        assertEquals(listOf(0L, 1_000L), times)
    }

    @Test
    fun aBurstIsGroupedAndFlushedOnTheTimer() = runTest {
        val source = MutableSharedFlow<Int>(extraBufferCapacity = 64)
        val out = mutableListOf<List<Int>>()
        val times = mutableListOf<Long>()
        val job = launch { source.batchedEvery(100).collect { out += it; times += currentTime } }
        runCurrent()

        source.emit(1)
        runCurrent()
        repeat(4) { source.emit(it + 2) }
        runCurrent()
        // The burst is held, not waiting for a further message to push it out.
        assertEquals(listOf(listOf(1)), out)
        advanceTimeBy(101)
        assertEquals(listOf(listOf(1), listOf(2, 3, 4, 5)), out)
        assertEquals(listOf(0L, 100L), times)
        job.cancel()
    }

    @Test
    fun theWindowClosesAfterAnEmptyPeriod() = runTest {
        val source = MutableSharedFlow<Int>(extraBufferCapacity = 64)
        val out = mutableListOf<List<Int>>()
        val job = launch { source.batchedEvery(100).collect { out += it } }
        runCurrent()

        source.emit(1)
        runCurrent()
        advanceTimeBy(500)
        // Idle again, so the next message goes straight out instead of waiting.
        source.emit(2)
        runCurrent()
        assertEquals(listOf(listOf(1), listOf(2)), out)
        job.cancel()
    }

    @Test
    fun heldItemsAreSentWhenTheUpstreamEnds() = runTest {
        val out = flow {
            emit(1)
            emit(2)
            emit(3)
        }.batchedEvery(100).toList()
        assertEquals(listOf(listOf(1), listOf(2, 3)), out)
    }

    @Test
    fun orderIsKeptAcrossBatches() = runTest {
        val out = flow {
            for (i in 1..50) {
                emit(i)
                delay(7)
            }
        }.batchedEvery(100).toList()
        assertEquals((1..50).toList(), out.flatten())
    }
}
