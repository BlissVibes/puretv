package com.puretv.twitch.core.perf

import kotlin.jvm.Synchronized
import kotlin.time.TimeSource

/**
 * Local-only timing marks for the moments viewers feel: how long the app takes
 * to show its first window, and how long a stream takes from the tap to its
 * first video frame.
 *
 * Nothing here leaves the device. Each app points [sink] at its own local log
 * (a file under the app data folder on Windows, logcat on Android and TV) so a
 * developer can compare before and after numbers by hand. With no sink set,
 * every call is a cheap no-op.
 */
object TimingLog {
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun log(event: String, elapsedMs: Long, detail: String = "") {
        val out = sink ?: return
        out(if (detail.isBlank()) "$event ${elapsedMs}ms" else "$event ${elapsedMs}ms $detail")
    }
}

/**
 * Times one stream open. Created when the viewer opens a channel; each stage is
 * logged once, as milliseconds since the open, so a re-render or a second
 * "playing" event cannot overwrite the first (and meaningful) measurement.
 * Marks arrive from the UI thread, player callbacks and network coroutines,
 * hence the lock.
 */
class StreamOpenTiming(private val channel: String) {
    private val started = TimeSource.Monotonic.markNow()
    private val logged = HashSet<String>()

    @Synchronized
    fun mark(stage: String) {
        if (logged.add(stage)) {
            TimingLog.log("stream.$stage", started.elapsedNow().inWholeMilliseconds, channel)
        }
    }
}
