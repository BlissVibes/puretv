package com.puretv.twitch.core.chat

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How often a busy chat updates the screen: about ten times a second. */
const val CHAT_BATCH_WINDOW_MS = 100L

/**
 * Groups a busy stream of items into lists, so a chat doing 30+ messages a second
 * updates the screen about ten times a second instead of once per message.
 *
 * A quiet chat pays nothing: an item that arrives while no window is open goes out
 * on its own at once and opens a [windowMs] window. Items arriving inside the
 * window are held and sent together when it ends (on a timer, so the last line of
 * a burst never waits for the next message). The window stays open while items
 * keep coming and closes after one empty period. Order is kept, and anything still
 * held when the upstream ends is sent before the flow completes.
 */
fun <T> Flow<T>.batchedEvery(windowMs: Long = CHAT_BATCH_WINDOW_MS): Flow<List<T>> = channelFlow {
    val lock = Mutex()
    val pending = ArrayList<T>()
    var window: Job? = null

    fun openWindow(): Job = launch {
        while (true) {
            delay(windowMs)
            val batch = lock.withLock {
                if (pending.isEmpty()) {
                    window = null
                    null
                } else {
                    ArrayList(pending).also { pending.clear() }
                }
            } ?: break
            send(batch)
        }
    }

    collect { item ->
        val sendNow = lock.withLock {
            if (window == null) {
                window = openWindow()
                true
            } else {
                pending += item
                false
            }
        }
        if (sendNow) send(listOf(item))
    }

    val rest = lock.withLock {
        window?.cancel()
        window = null
        ArrayList(pending).also { pending.clear() }
    }
    if (rest.isNotEmpty()) send(rest)
}
