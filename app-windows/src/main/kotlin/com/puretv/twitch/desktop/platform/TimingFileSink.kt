package com.puretv.twitch.desktop.platform

import com.puretv.twitch.core.perf.TimingLog
import java.io.File
import java.lang.management.ManagementFactory
import java.time.LocalDateTime

/**
 * Writes [TimingLog] marks to `%APPDATA%\PureTwitch\timing.log` so cold start and
 * time to first frame can be compared before and after a change. Local only:
 * the file is never uploaded, and it is capped so it cannot grow without bound.
 */
object TimingFileSink {
    private const val MAX_BYTES = 256 * 1024L

    private val file: File by lazy {
        AppPaths.dataDir.resolve("timing.log")
    }

    fun install() {
        TimingLog.sink = ::append
    }

    /** Milliseconds since the JVM started, which is when the user launched PureTV. */
    fun sinceProcessStartMs(): Long =
        runCatching { System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime }.getOrDefault(-1L)

    @Synchronized
    private fun append(line: String) {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.length() > MAX_BYTES) file.writeText("")
            file.appendText("${LocalDateTime.now()} $line\n")
        }
    }
}
