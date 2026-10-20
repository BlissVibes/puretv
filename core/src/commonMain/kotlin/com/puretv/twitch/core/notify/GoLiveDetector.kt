package com.puretv.twitch.core.notify

import com.puretv.twitch.core.model.StreamInfo

/** The streams to announce after one poll, and the state to keep for the next one. */
data class GoLiveDecision(val toNotify: List<StreamInfo>, val nextState: LiveNotifyState)

/**
 * Decides which of the channels the viewer asked to be told about just went live. Pure (no I/O, no clock reads) so
 * the rules that keep notifications honest are tested once and shared by all three
 * apps:
 *
 *  1. Keyed on the Twitch STREAM id, not the login. A new broadcast has a new id and
 *     is announced; an app restart in the middle of a broadcast sees the same id and
 *     stays quiet.
 *  2. Seeds silently. With no saved state, or state from another account, the current
 *     live set is recorded and nobody is announced, so the first run is not a flood.
 *  3. A stream that drops out of one poll and comes back with the same id inside
 *     [SEEN_RETENTION_MS] is not announced again.
 *  4. At most one announcement per channel per [RENOTIFY_COOLDOWN_MS], so a streamer
 *     whose broadcast keeps restarting (new ids) does not spam.
 *  5. Channels not asked about and a switched-off toggle still update the state, so turning
 *     alerts back on later does not announce streams that were already running.
 *     With alerts off the state is also left unseeded, and each app calls
 *     LiveFollowsMonitor.alertsStopped when alerts stop (switched off, signed out),
 *     so the first poll after they resume seeds silently (rule 2) even when nothing
 *     polled in between. Seeding keeps the cooldowns of the same account.
 *  6. A stream that has already been live longer than [LATE_LIMIT_MS] is not
 *     announced: the viewer was away (app closed, phone in Doze), and a toast for a
 *     three-hour-old broadcast is noise rather than news.
 *  7. Callers never evaluate a failed poll, so an error never reads as "everyone went
 *     offline" and never overwrites the state.
 *  8. [evaluate]'s `watched` is the set of channels the viewer opted in to. Only those
 *     are ever announced, and one that was not in the previous poll's set is recorded
 *     without an announcement, so tapping the bell on a channel that is live right now
 *     does not announce the broadcast being watched. A null `watched` means every channel
 *     in `live` counts (the detector's own tests use it).
 */
object GoLiveDetector {
    const val SEEN_RETENTION_MS = 30 * 60_000L
    const val RENOTIFY_COOLDOWN_MS = 10 * 60_000L
    const val LATE_LIMIT_MS = 90 * 60_000L
    private const val NOTIFIED_RETENTION_MS = 24 * 60 * 60_000L

    fun evaluate(
        prev: LiveNotifyState,
        userId: String,
        live: List<StreamInfo>,
        nowMs: Long,
        enabled: Boolean,
        watched: Set<String>? = null,
    ): GoLiveDecision {
        val reseed = !prev.seeded || prev.userId != userId
        val base = when {
            !reseed -> prev
            prev.userId == userId -> LiveNotifyState(userId = userId, lastNotifiedAtMs = prev.lastNotifiedAtMs)
            else -> LiveNotifyState(userId = userId)
        }
        val watchedLower = watched?.mapTo(HashSet()) { it.lowercase() }
        val notify = mutableListOf<StreamInfo>()
        val notified = base.lastNotifiedAtMs.toMutableMap()
        if (!reseed && enabled) {
            for (stream in live) {
                val login = stream.userLogin.lowercase()
                // Compared against every remembered stream, expired or not, so a long
                // gap between polls never re-announces a broadcast still running.
                if (base.seen[login]?.streamId == stream.id) continue
                if (watchedLower != null) {
                    if (login !in watchedLower) continue
                    // Rule 8: a channel the viewer only just opted in to is recorded, not announced.
                    if (login !in base.watched) continue
                }
                val last = notified[login]
                if (last != null && nowMs - last < RENOTIFY_COOLDOWN_MS) continue
                val started = parseStartedAtMs(stream.startedAt)
                if (started != null && nowMs - started > LATE_LIMIT_MS) continue
                notify += stream
                notified[login] = nowMs
            }
        }
        val seen = base.seen.filterValues { nowMs - it.lastSeenAtMs < SEEN_RETENTION_MS }.toMutableMap()
        live.forEach { seen[it.userLogin.lowercase()] = SeenStream(it.id, nowMs) }
        val next = LiveNotifyState(
            userId = userId,
            // Rule 5: nothing is announced while alerts are off, so nothing is
            // compared against either; the first poll with them on seeds afresh.
            seeded = enabled,
            seen = seen,
            watched = watchedLower ?: base.watched,
            lastNotifiedAtMs = notified.filterValues { nowMs - it < NOTIFIED_RETENTION_MS },
        )
        return GoLiveDecision(notify, next)
    }

    /**
     * Helix `started_at` ("2026-10-19T10:00:00Z", always UTC) to epoch milliseconds,
     * or null when blank or not in that shape. Hand-rolled because core carries no
     * date library and this is the only timestamp it needs to read.
     */
    fun parseStartedAtMs(iso: String): Long? {
        val m = ISO_UTC.matchEntire(iso.trim()) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        val year = y.toInt()
        val month = mo.toInt()
        val day = d.toInt()
        if (month !in 1..12 || day !in 1..31) return null
        val days = daysFromCivil(year, month, day)
        return ((days * 24 + h.toLong()) * 60 + mi.toLong()) * 60_000 + s.toLong() * 1_000
    }

    private val ISO_UTC = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?Z""")

    /** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}
