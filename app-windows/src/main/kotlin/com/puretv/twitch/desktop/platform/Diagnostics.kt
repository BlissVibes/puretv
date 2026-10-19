package com.puretv.twitch.desktop.platform

import java.io.File
import java.time.LocalDateTime

/**
 * Local-only troubleshooting. [installCrashLog] writes uncaught errors to
 * `%APPDATA%\PureTwitch\crash.log` (a GUI app has no console, so they used to
 * vanish), and [report] assembles what a bug report needs into text the viewer
 * copies themselves from Settings. Nothing here is ever sent anywhere.
 *
 * The report deliberately leaves out tokens and signed URLs: [scrub] removes
 * anything that looks like one, as a backstop for log lines that quote a URL.
 */
object Diagnostics {
    private const val MAX_CRASH_LOG_BYTES = 512 * 1024L

    private val dataDir: File by lazy {
        File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PureTwitch")
    }

    /** Records every uncaught exception, then hands it to whatever handler was there before. */
    fun installCrashLog() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            appendCrash(thread, error)
            previous?.uncaughtException(thread, error)
        }
    }

    @Synchronized
    private fun appendCrash(thread: Thread, error: Throwable) {
        runCatching {
            val file = File(dataDir, "crash.log")
            file.parentFile?.mkdirs()
            if (file.length() > MAX_CRASH_LOG_BYTES) file.writeText("")
            file.appendText("${LocalDateTime.now()} [${thread.name}] ${error.stackTraceToString()}\n")
        }
    }

    /**
     * The text behind "Copy diagnostics". [sections] are app-supplied name/value
     * blocks (version, player, last stream failure...); the tails of the local
     * logs are appended after them.
     */
    fun report(sections: List<Pair<String, String>>): String = scrub(
        buildString {
            appendLine("PureTV diagnostics, ${LocalDateTime.now()}")
            appendLine("Windows: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
            appendLine("Java: ${System.getProperty("java.version")}, max memory ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB")
            for ((name, value) in sections) {
                appendLine()
                appendLine("== $name")
                appendLine(value.trimEnd())
            }
            for ((name, lines) in listOf("timing.log" to 40, "update.log" to 40, "crash.log" to 80)) {
                appendLine()
                appendLine("== $name (last $lines lines)")
                appendLine(tail(File(dataDir, name), lines).ifBlank { "(empty)" })
            }
        },
    )

    private fun tail(file: File, lines: Int): String =
        runCatching { if (file.isFile) file.readLines().takeLast(lines).joinToString("\n") else "" }.getOrDefault("")

    private val SECRET_PARAMS = Regex("""(?i)\b(token|sig|signature|access_token|refresh_token|client_secret|oauth)=([^&\s"']+)""")
    private val BEARER = Regex("""(?i)(bearer|oauth)\s+[a-z0-9]{10,}""")

    /** Blanks query values and headers that carry credentials or signed playback tokens. */
    internal fun scrub(text: String): String =
        text.replace(SECRET_PARAMS) { "${it.groupValues[1]}=<removed>" }
            .replace(BEARER) { "${it.groupValues[1]} <removed>" }
}
