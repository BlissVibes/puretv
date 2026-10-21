package com.puretv.twitch.desktop.ui

import com.puretv.twitch.core.vod.ResumePolicy
import com.puretv.twitch.core.vod.WatchProgress

/** A past video named by a pasted link or id, and where the link says to start. */
/** [certain] is false for a bare number, which could just as well be a channel name. */
data class ParsedVideo(val videoId: String, val startMs: Long = 0, val certain: Boolean = true)

/**
 * The pure rules of a past-video tile: where it starts, where a retry or a moved window
 * picks up, how far a seek may go and when progress is worth saving. Kept out of the view
 * model so each rule is tested on its own.
 */
object VodTileLogic {
    /** Under this a saved place is not worth jumping to, and nothing is saved. */
    const val MIN_RESUME_MS = 20_000L

    /** Seek distances of the keys: arrows, J and L, and Shift with the arrows. */
    const val ARROW_STEP_MS = 5_000L
    const val ARROW_STEP_LARGE_MS = 30_000L
    const val JUMP_STEP_MS = 10_000L

    /** How often a playing tile saves its place. */
    const val SAVE_EVERY_MS = 10_000L

    /** Twitch video ids are long; a shorter bare number is more likely a channel name. */
    const val MIN_BARE_ID_DIGITS = 9

    private val idPattern = Regex("""^(v)?(\d{1,15})$""", RegexOption.IGNORE_CASE)
    private val urlPattern = Regex("""twitch\.tv/(?:[^/?#\s]+/)?videos?/(\d{1,15})""", RegexOption.IGNORE_CASE)
    private val timePattern = Regex("""[?&]t=(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?(?=&|#|$)""", RegexOption.IGNORE_CASE)

    /**
     * The video in [input]: a twitch.tv/videos/ID link (with an optional `t=1h2m3s` start), a
     * v-prefixed id as shown in the player, or a bare number of [MIN_BARE_ID_DIGITS] or more
     * digits (not [ParsedVideo.certain]: try it as a channel first). Null for anything else,
     * a short bare number included.
     */
    fun parseVideo(input: String): ParsedVideo? {
        val text = input.trim()
        if (text.isEmpty()) return null
        idPattern.find(text)?.let { match ->
            val prefixed = match.groupValues[1].isNotEmpty()
            val digits = match.groupValues[2]
            return if (prefixed || digits.length >= MIN_BARE_ID_DIGITS) ParsedVideo(digits, certain = prefixed) else null
        }
        val id = urlPattern.find(text)?.groupValues?.get(1) ?: return null
        val time = timePattern.find(text)
        val seconds = if (time == null) 0L else {
            val (h, m, s) = time.destructured
            (h.toLongOrNull() ?: 0) * 3600 + (m.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
        }
        return ParsedVideo(id, seconds * 1000)
    }

    /**
     * Where a tile starts. A place asked for ([requestedMs], the player screen handing over
     * its video) wins. Otherwise the saved place, when it is far enough in to matter (the
     * tile resumes silently, there is no prompt). Otherwise the beginning.
     */
    fun initialStartMs(requestedMs: Long, saved: WatchProgress?): Long {
        if (requestedMs > 0) return requestedMs
        val resume = saved?.let { ResumePolicy.resumePositionMs(it) } ?: return 0
        return if (resume >= MIN_RESUME_MS) resume else 0
    }

    /**
     * Where a reload picks up: the last place the tile was seen at, never the beginning
     * because a retry happened. Only a tile that never played falls back to its start.
     * A place at the very end counts as the end, so a finished video is not replayed.
     */
    fun retryStartMs(lastKnownMs: Long, startMs: Long, durationMs: Long): Long {
        val known = if (lastKnownMs > 0) lastKnownMs else startMs
        return if (durationMs > 0) known.coerceIn(0, durationMs) else known.coerceAtLeast(0)
    }

    /** [targetMs] kept inside the video. With no known length only the start is a limit. */
    fun clampSeek(targetMs: Long, durationMs: Long): Long =
        if (durationMs > 0) targetMs.coerceIn(0, durationMs) else targetMs.coerceAtLeast(0)

    /** The target of a relative seek from [positionMs]; null while the video cannot seek yet. */
    fun seekTarget(positionMs: Long, deltaMs: Long, durationMs: Long, seekable: Boolean): Long? =
        if (!seekable || durationMs <= 0) null else clampSeek(positionMs + deltaMs, durationMs)

    /**
     * Where a reload picks up, or null when the video is at its end and should stay ended
     * (pressing play then starts it over). Uses [lastGoodMs], the last place really seen.
     */
    fun reloadStartMs(lastGoodMs: Long, startMs: Long, durationMs: Long): Long? =
        if (lastGoodMs > 0 && com.puretv.twitch.desktop.player.VodPlayback.isEnded(lastGoodMs, durationMs)) null
        else retryStartMs(lastGoodMs, startMs, durationMs)

    /** True once the clock has caught up with a seek to [targetMs], so the bar can stop showing the target. */
    fun seekSettled(targetMs: Long, positionMs: Long): Boolean = kotlin.math.abs(positionMs - targetMs) <= SEEK_SETTLE_MS

    /** How long the bar keeps showing a seek target while the clock catches up. */
    const val SEEK_TIMEOUT_MS = 1_000L
    private const val SEEK_SETTLE_MS = 1_500L

    /**
     * The progress entry to save for a playing video: the new place and length, and for each
     * piece of metadata the tile's value unless it is blank, in which case the entry already
     * saved keeps its own. A tile opened from a pasted link may not know them yet.
     */
    fun mergedProgress(
        existing: WatchProgress?,
        videoId: String,
        title: String,
        channelLogin: String,
        thumbnailUrl: String,
        positionMs: Long,
        durationMs: Long,
        now: Long,
    ): WatchProgress = WatchProgress(
        vodId = videoId,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = now,
        title = title.ifBlank { existing?.title.orEmpty() },
        channelLogin = channelLogin.ifBlank { existing?.channelLogin.orEmpty() },
        thumbnailUrl = thumbnailUrl.ifBlank { existing?.thumbnailUrl.orEmpty() },
    )

    /** The slider position (0 to 1) of [positionMs] in a video of [durationMs]. */
    fun fraction(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    /** The place a slider fraction stands for. */
    fun positionAt(fraction: Float, durationMs: Long): Long =
        clampSeek((fraction.coerceIn(0f, 1f) * durationMs).toLong(), durationMs)

    /** True when the place is worth keeping for Continue Watching. */
    fun shouldSave(positionMs: Long, durationMs: Long): Boolean = durationMs > 0 && positionMs >= MIN_RESUME_MS

}
