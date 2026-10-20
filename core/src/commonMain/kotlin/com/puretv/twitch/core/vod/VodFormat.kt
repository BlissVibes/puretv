package com.puretv.twitch.core.vod

/**
 * Small display helpers the phone and TV VOD screens share, kept here so both
 * read the same and stay testable (app-tv has no test setup).
 */
object VodFormat {

    /** A position or length as `M:SS`, or `H:MM:SS` from an hour up. */
    fun timecode(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "$h:${m.twoDigits()}:${s.twoDigits()}" else "$m:${s.twoDigits()}"
    }

    /**
     * Fills Helix's VOD thumbnail template, which uses `%{width}`/`%{height}`
     * (live previews use `{width}`). Twitch serves a placeholder path containing
     * `_404/` for a video that is still processing; that is still a valid image.
     */
    fun thumbnail(template: String, width: Int = 320, height: Int = 180): String =
        template.replace("%{width}", width.toString()).replace("%{height}", height.toString())

    /** "Today", "Yesterday", "3 days ago", "2 weeks ago", "5 months ago", "1 year ago". */
    fun age(createdAtMs: Long, nowMs: Long): String {
        val days = ((nowMs - createdAtMs).coerceAtLeast(0) / DAY_MS).toInt()
        return when {
            days < 1 -> "Today"
            days < 2 -> "Yesterday"
            days < 14 -> "$days days ago"
            days < 60 -> "${days / 7} weeks ago"
            days < 365 -> "${days / 30} months ago"
            days < 730 -> "1 year ago"
            else -> "${days / 365} years ago"
        }
    }

    private fun Long.twoDigits(): String = if (this < 10) "0$this" else toString()

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
