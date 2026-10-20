package com.puretv.twitch.core.notify

import com.puretv.twitch.core.model.StreamInfo

/** A notification's two lines. */
data class GoLiveMessage(val title: String, val body: String)

/**
 * The words every app uses for a go-live alert, kept in one place so the Windows
 * toast, the phone notification and the TV banner say the same thing.
 */
object GoLiveText {
    /** Long enough for a stream title to read, short enough for a toast's second line. */
    const val MAX_BODY = 80

    /** How the channel is named: its display name, or the login when that is blank. */
    fun name(stream: StreamInfo): String = stream.userName.trim().ifBlank { stream.userLogin }

    /** "Alice is live" with the stream title (or category) underneath. */
    fun forStream(stream: StreamInfo): GoLiveMessage = GoLiveMessage("${name(stream)} is live", detail(stream))

    /**
     * One message for everything that went live in a single poll. One stream gets its
     * own title; two or three are named together; more become a count, so a busy
     * evening is one alert rather than a stack of them.
     */
    fun forBatch(streams: List<StreamInfo>): GoLiveMessage {
        val names = streams.map(::name)
        return when (streams.size) {
            0 -> GoLiveMessage("", "")
            1 -> forStream(streams.single())
            2, 3 -> GoLiveMessage("${joinNames(names)} are live", cut(streams.joinToString(", ") { detail(it) }))
            else -> GoLiveMessage(
                "${streams.size} channels you follow went live",
                "${names.take(3).joinToString(", ")} and ${streams.size - 3} more",
            )
        }
    }

    private fun detail(stream: StreamInfo): String {
        val title = stream.title.trim()
        return when {
            title.isNotEmpty() -> cut(title)
            stream.gameName.isNotBlank() -> "Playing ${stream.gameName.trim()}"
            else -> "Streaming now"
        }
    }

    private fun joinNames(names: List<String>): String =
        if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()

    private fun cut(text: String): String =
        if (text.length <= MAX_BODY) text else text.take(MAX_BODY - 3).trimEnd() + "..."
}
