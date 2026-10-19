package com.puretv.twitch.core.adblock

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One media-playlist refresh's worth of ad blocking, shared by all three apps so
 * the rules live in one place. Windows' `LocalStreamProxy` calls it from its
 * /variant route; the Android and TV playlist interceptors call it from OkHttp.
 *
 * For each refresh:
 *   1. Already clean: pass it through (green pill).
 *   2. Ad present: try the backup player-type swap for a clean playlist at the
 *      same quality (green pill, no gap). Bounded by [backupTimeoutMs], because
 *      the player is waiting on this refresh and a slow token mint must not
 *      stall playback longer than the ad would have.
 *   3. No clean backup: strip the pod (yellow pill, the player briefly waits).
 *   4. Stripping left NO segments (issue #22: an all-ad preroll window has no
 *      content to splice back to). With [holdEmptyWindows] on, the refresh is
 *      held: re-fetch and re-clean every [holdPollMs] until content appears,
 *      for at most [holdMaxMs]. The player sees a slow playlist load (a
 *      buffering spinner) instead of an unplayable empty playlist, and never an
 *      ad. Only ExoPlayer turns this on; VLC handles the empty window itself
 *      and Windows has its own stall watchdog.
 *
 * Ad-free results (1 and 2) end at the live edge when [lowLatency] is on: the
 * playlist refreshes every segment, and for a player on one rendition Twitch's
 * prefetch hints become ordinary segments ([TwitchPrefetch.liveEdge]). Results
 * that still had ads (3, or a failure) keep Twitch's own target duration and
 * never keep prefetch hints, since those can be ad segments.
 *
 * Every result then goes through [HlsTimelineNormalizer], because a backup
 * session numbers its segments differently from the primary one and the
 * player would otherwise see the live window jump.
 */
class MediaPlaylistCleaner(
    private val adBlockEngine: AdBlockEngine,
    private val backupStreamResolver: BackupStreamResolver,
    private val httpClient: HttpClient,
    private val nowMs: () -> Long,
    private val holdEmptyWindows: Boolean,
    private val backupTimeoutMs: Long = 4_000,
    private val holdPollMs: Long = 2_000,
    private val holdMaxMs: Long = 40_000,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /** The low-latency setting, read on every refresh. */
    private val lowLatency: () -> Boolean = { false },
) {
    private val timeline = HlsTimelineNormalizer()

    enum class Strategy { CLEAN, BACKUP, STRIP, FAILED }

    /** The quality the ad-bearing variant plays at, so a backup can match it. */
    data class VariantQuality(val resolution: String, val frameRate: Double)

    data class Result(
        val content: String,
        val strategy: Strategy,
        val backupPlayerType: String? = null,
        val adSegmentsRemoved: Int = 0,
        /** How long the refresh was held waiting for content (issue #22), or 0. */
        val heldMs: Long = 0,
        val sequenceNormalized: Boolean = false,
        val segmentsTrimmed: Int = 0,
    )

    /**
     * Cleans one media playlist. [variantUrl] is the URL the player asked for; it
     * keys the timeline state and is re-fetched while holding. Without both
     * [channelLogin] and [quality] there is nothing to mint a backup for, so ads
     * go straight to stripping. [forceAd] is the Windows ad-simulation test hook.
     * [isCancelled] lets a hold end early when the player gives up on the request.
     * [singleRendition] says the player plays only this rendition (no automatic
     * quality), the one case where prefetch hints are safe to promote.
     */
    suspend fun clean(
        variantUrl: String,
        raw: String,
        channelLogin: String?,
        quality: VariantQuality?,
        forceAd: Boolean = false,
        isCancelled: () -> Boolean = { false },
        singleRendition: Boolean = false,
    ): Result {
        var result = cleanOnce(raw, channelLogin, quality, forceAd, singleRendition)
        if (holdEmptyWindows && result.strategy == Strategy.STRIP && segmentCount(result.content) == 0) {
            result = hold(variantUrl, result, channelLogin, quality, forceAd, isCancelled, singleRendition)
        }
        val normalized = timeline.normalize(variantUrl, result.content)
        return result.copy(
            content = normalized.content,
            sequenceNormalized = normalized.sequenceNormalized,
            segmentsTrimmed = normalized.segmentsTrimmed,
        )
    }

    private suspend fun cleanOnce(
        raw: String,
        channelLogin: String?,
        quality: VariantQuality?,
        forceAd: Boolean,
        singleRendition: Boolean,
    ): Result {
        if (!forceAd && !AdMarkers.containsAds(raw)) {
            adBlockEngine.reportAdBlocked()
            return Result(atLiveEdge(raw, singleRendition), Strategy.CLEAN)
        }

        if (!channelLogin.isNullOrBlank() && quality != null) {
            val backup = withTimeoutOrNull(backupTimeoutMs) {
                runCatching {
                    backupStreamResolver.resolveAdFreeMediaPlaylist(
                        channelLogin = channelLogin,
                        targetResolution = quality.resolution,
                        targetFrameRate = quality.frameRate,
                        nowMs = nowMs(),
                    )
                }.getOrNull()
            }
            if (backup != null) {
                adBlockEngine.reportAdBlocked()
                return Result(
                    atLiveEdge(backup.mediaPlaylist, singleRendition),
                    Strategy.BACKUP,
                    backupPlayerType = backup.playerType,
                )
            }
        }

        return try {
            val filtered = adBlockEngine.filterPlaylist(raw)
            adBlockEngine.reportFiltered(filtered)
            Result(TwitchPrefetch.strip(filtered.content), Strategy.STRIP, adSegmentsRemoved = filtered.adSegmentsRemoved)
        } catch (t: Throwable) {
            // Never pass ads off as success: the pill goes red, and the raw
            // playlist keeps playback alive instead of dead-ending.
            adBlockEngine.reportResolutionFailure()
            Result(TwitchPrefetch.strip(raw), Strategy.FAILED)
        }
    }

    /** An ad-free playlist, reaching as close to live as the low-latency setting allows. */
    private fun atLiveEdge(playlist: String, singleRendition: Boolean): String =
        if (lowLatency()) TwitchPrefetch.liveEdge(playlist, promotePrefetch = singleRendition) else playlist

    private suspend fun hold(
        variantUrl: String,
        emptyWindow: Result,
        channelLogin: String?,
        quality: VariantQuality?,
        forceAd: Boolean,
        isCancelled: () -> Boolean,
        singleRendition: Boolean,
    ): Result {
        val startedAt = nowMs()
        var latest = emptyWindow
        while (nowMs() - startedAt < holdMaxMs && !isCancelled()) {
            pause(holdPollMs)
            if (isCancelled()) break
            val response = runCatching { httpClient.get(variantUrl) }.getOrNull() ?: continue
            // The variant is gone (stream ended or the session expired): stop
            // holding and let the player's own error handling take over.
            if (!response.status.isSuccess()) break
            val fresh = runCatching { response.bodyAsText() }.getOrNull() ?: continue
            latest = cleanOnce(fresh, channelLogin, quality, forceAd, singleRendition)
            if (segmentCount(latest.content) > 0) break
        }
        return latest.copy(heldMs = nowMs() - startedAt)
    }

    companion object {
        /** Segment URIs in a media playlist: every non-blank line that is not a tag. */
        fun segmentCount(playlist: String): Int =
            playlist.lineSequence().count { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() && !trimmed.startsWith("#")
            }
    }
}
