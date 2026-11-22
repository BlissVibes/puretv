package com.puretv.twitch.core.stream

/**
 * The wall-clock timeline of a live media playlist: when each listed segment was
 * broadcast (Twitch's `#EXT-X-PROGRAM-DATE-TIME`), and Twitch's own clock at the
 * moment it wrote the playlist (`X-SERVER-TIME`). Together they say how far behind
 * live a player is without trusting this computer's clock, which can be many
 * seconds off.
 */
data class HlsLiveTiming(
    /** Listed segments, oldest first, with their start as epoch milliseconds. */
    val segments: List<Segment>,
    /** Twitch's clock when the playlist was written, epoch milliseconds, when it says. */
    val serverTimeMs: Long?,
) {
    data class Segment(val startMs: Long, val durationMs: Long)

    /**
     * Where VLC starts playing this playlist, as the broadcast time of its first frame.
     *
     * libVLC's adaptive demuxer starts at the segment that contains "the start of the
     * newest segment, less the live delay" (its `adaptive-livedelay`, 15 s unless set).
     * Measured with VLC 3.0.24 on Twitch's 2 s segments: 15 s starts 9 segments from the
     * end (18 s back), 6 s starts 4 from the end (8 s back). Asking for more than the
     * playlist holds starts at its first segment.
     */
    fun vlcStartMs(liveDelayMs: Long): Long? {
        val newest = segments.lastOrNull() ?: return null
        val target = newest.startMs - liveDelayMs
        return (segments.lastOrNull { it.startMs <= target } ?: segments.first()).startMs
    }

    companion object {
        private const val DATE_TIME = "#EXT-X-PROGRAM-DATE-TIME:"
        private const val INF = "#EXTINF:"
        private val SERVER_TIME = Regex("X-SERVER-TIME=\"([0-9]+(?:\\.[0-9]+)?)\"")

        /** Null when the playlist has no dated segments (nothing to measure against). */
        fun parse(playlist: String): HlsLiveTiming? {
            val segments = ArrayList<Segment>()
            var nextStart: Long? = null
            var duration: Long? = null
            for (raw in playlist.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith(DATE_TIME) -> parseInstantMs(line.substring(DATE_TIME.length))?.let { nextStart = it }
                    line.startsWith(INF) ->
                        duration = line.substring(INF.length).substringBefore(',').trim().toDoubleOrNull()?.let { (it * 1000).toLong() }
                    line.isEmpty() || line.startsWith("#") -> Unit
                    else -> {
                        // A segment's address. Without a date of its own it follows the one before.
                        val start = nextStart
                        val d = duration
                        if (start != null && d != null) {
                            segments += Segment(start, d)
                            nextStart = start + d
                        }
                        duration = null
                    }
                }
            }
            if (segments.isEmpty()) return null
            val server = SERVER_TIME.find(playlist)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            return HlsLiveTiming(segments, server)
        }

        /** `2026-11-22T09:11:47.771Z` as epoch milliseconds; null for anything else. UTC only. */
        fun parseInstantMs(text: String): Long? {
            val t = text.trim()
            if (t.length < 20 || t[4] != '-' || t[7] != '-' || (t[10] != 'T' && t[10] != 't') || t[13] != ':' || t[16] != ':') return null
            if (!t.endsWith("Z") && !t.endsWith("z")) return null
            val year = t.substring(0, 4).toIntOrNull() ?: return null
            val month = t.substring(5, 7).toIntOrNull() ?: return null
            val day = t.substring(8, 10).toIntOrNull() ?: return null
            val hour = t.substring(11, 13).toIntOrNull() ?: return null
            val minute = t.substring(14, 16).toIntOrNull() ?: return null
            val second = t.substring(17, 19).toIntOrNull() ?: return null
            if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null
            var millis = 0L
            val rest = t.substring(19, t.length - 1)
            if (rest.isNotEmpty()) {
                if (rest[0] != '.') return null
                val digits = rest.substring(1)
                if (digits.isEmpty() || digits.any { !it.isDigit() }) return null
                millis = digits.padEnd(3, '0').substring(0, 3).toLong()
            }
            return ((daysFromCivil(year, month, day) * 24 + hour) * 60 + minute) * 60_000L + second * 1000L + millis
        }

        /** Days from 1970-01-01 to a date in the proleptic Gregorian calendar. */
        private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = (if (y >= 0) y else y - 399) / 400
            val yearOfEra = y - era * 400
            val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
            val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
            return era * 146_097 + dayOfEra - 719_468
        }
    }
}
