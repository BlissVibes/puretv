package com.puretv.twitch.core.adblock

import com.puretv.twitch.core.model.AdBlockStrategyResult
import com.puretv.twitch.core.model.CleanStreamResult
import com.puretv.twitch.core.model.FilteredPlaylist
import com.puretv.twitch.core.model.PlaylistVariant
import com.puretv.twitch.core.model.StreamQuality
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.util.encodeBase64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.jvm.Synchronized

/**
 * SECTION 04 [CRITICAL — MOST IMPORTANT FEATURE]
 *
 * Three-tier fallback chain that guarantees the user is never shown a Twitch ad:
 *
 *   1. PROXY ROUTER     — route the ~2KB playlist through an ad-light-region proxy
 *   2. MANIFEST REWRITE — strip ad markers locally if the proxy is unavailable
 *   3. BLACK FRAME      — last resort, implemented at the player layer (mute + overlay)
 *      when an ad segment slips through both of the above (see [AdBlockStatus.PASSTHROUGH_BLOCKED]
 *      and the player-side detectors in app-android/app-tv/app-windows).
 *
 * Rule of thumb (Final Agent Instructions #5): "A stream playing with ads is a failure state."
 * Every code path here must end in either a clean playlist or an explicit, observable
 * failure — never a silent ad.
 */
class AdBlockEngine(
    private val config: AdBlockConfig,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    private val _status = MutableStateFlow(AdBlockStatus.UNKNOWN)
    val status: StateFlow<AdBlockStatus> = _status.asStateFlow()

    // Multi-stream shows one pill per channel, so every report can also name its
    // channel. The app-wide [status] above is still written exactly as before; this
    // map is an immutable snapshot swapped atomically, because several channels
    // report from different network threads at once. Keys are lower case.
    private val channelStatuses = MutableStateFlow<Map<String, AdBlockStatus>>(emptyMap())

    /** The status of one channel alone: UNKNOWN until its first report. */
    fun statusFor(channel: String): Flow<AdBlockStatus> {
        val key = channel.lowercase()
        return channelStatuses.map { it[key] ?: AdBlockStatus.UNKNOWN }.distinctUntilChanged()
    }

    /** Forgets [channel]'s status, for a channel that is no longer being watched. */
    fun forgetChannel(channel: String) {
        val key = channel.lowercase()
        channelStatuses.update { if (key in it) it - key else it }
    }

    private fun publish(status: AdBlockStatus, channel: String?) {
        _status.value = status
        if (channel.isNullOrBlank()) return
        val key = channel.lowercase()
        channelStatuses.update { known ->
            if (known[key] == status) return@update known
            val next = known + (key to status)
            // Phone and TV report per channel too but never forget one; keep the map small.
            if (next.size > MAX_CHANNEL_STATUSES) next - next.keys.first() else next
        }
    }

    private val rewriter = ManifestRewriter()
    private val healthTracker = ProxyHealthTracker()

    /**
     * Runtime master switch for the in-process interceptor path (Android). When a
     * user turns ad blocking off in Settings, the interceptor consults this and
     * passes playlists through untouched. Volatile because it is written from the
     * settings collector and read on the player's network threads. Desktop uses
     * [AdBlockStrategy.DISABLED] via config and ignores this flag.
     */
    @Volatile
    var isEnabled: Boolean = true
        private set

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            _status.value = AdBlockStatus.DISABLED
            channelStatuses.update { known -> known.mapValues { AdBlockStatus.DISABLED } }
        }
    }

    /**
     * Called when the viewer opens a stream. This engine is an app-wide singleton,
     * so without a reset the previous channel's last status (say, yellow "skipping
     * ad") stayed on the new channel's pill until its first playlist refresh.
     */
    fun resetForNewStream(channel: String? = null) {
        publish(if (isEnabled) AdBlockStatus.UNKNOWN else AdBlockStatus.DISABLED, channel)
    }

    /**
     * Returns a clean m3u8 master/media playlist URL or content that the player
     * can consume without showing ads. Tries strategies in order based on
     * [AdBlockConfig.strategy] and current proxy health.
     */
    suspend fun resolveCleanStream(
        masterPlaylistUrl: String,
        quality: StreamQuality = StreamQuality.AUTO,
        /**
         * The master body when the caller already holds it (stream resolution
         * fetches and validates it). Skips a second usher round trip on every
         * stream open.
         */
        prefetchedMaster: String? = null,
        /** The channel this master belongs to, so its pill can be reported on its own (multi-stream). */
        channel: String? = null,
    ): CleanStreamResult {
        if (config.strategy == AdBlockStrategy.DISABLED) {
            publish(AdBlockStatus.DISABLED, channel)
            return passthrough(masterPlaylistUrl, AdBlockStrategyResult.DISABLED)
        }

        // Strategy 1 only runs if the user has actually configured an upstream
        // proxy endpoint. The 2026 default is empty (see AdBlockConfig docs) —
        // without this short-circuit we'd burn a network round-trip per stream
        // open hitting a known-dead URL before falling through.
        val hasUpstream = !config.customProxyEndpoint.isNullOrBlank() || config.proxyEndpoint.isNotBlank()
        if (config.strategy == AdBlockStrategy.PROXY_PRIMARY && hasUpstream && healthTracker.isHealthy()) {
            fetchViaProxy(masterPlaylistUrl)?.let { proxied ->
                publish(AdBlockStatus.AD_BLOCKED, channel)
                healthTracker.recordSuccess()
                return CleanStreamResult.Success(
                    playlistContent = proxied,
                    variants = emptyList(),
                    strategyUsed = AdBlockStrategyResult.PROXY,
                )
            }
            healthTracker.recordFailure()
        }

        if (config.fallbackToManifestRewrite) {
            val raw = prefetchedMaster ?: fetchRaw(masterPlaylistUrl)
            if (raw != null) {
                val filtered = rewriter.filter(raw)
                publish(if (filtered.containedAds) AdBlockStatus.AD_FILTERED else AdBlockStatus.AD_BLOCKED, channel)
                return CleanStreamResult.Success(
                    playlistContent = filtered.content,
                    variants = emptyList(),
                    strategyUsed = AdBlockStrategyResult.MANIFEST_REWRITE,
                )
            }
        }

        // Both network-level strategies failed. The player layer's black-frame
        // fallback (Section 4.4) takes over from here — we report the failure
        // state so the UI can show "AD BLOCK OFF" and the player can engage
        // its segment-anomaly detector.
        publish(AdBlockStatus.AD_BLOCK_OFF, channel)
        return CleanStreamResult.Failure("Both proxy and manifest-rewrite strategies failed")
    }

    /**
     * Called on every playlist refresh (live playlists refresh roughly every
     * ~2 seconds). Cheap, synchronous, no network — pure text filtering so it
     * can run on the player's polling thread without adding latency.
     */
    fun filterPlaylist(playlistContent: String): FilteredPlaylist {
        val filtered = rewriter.filter(playlistContent)
        // Fail-safe (audit AD-1, Rule #5 "a stream playing with ads is a failure
        // state"): the normal pass starts in content, so a pod whose OPENING marker
        // has scrolled out of the live window leaks its leading (mid-pod) segments
        // as content. Re-strip assuming we start mid-pod when an ad is detected and
        // EITHER:
        //   (a) the normal pass stripped nothing at all (the classic AD-1 case), OR
        //   (b) it stripped a LATER, fully-marked pod (adSegmentsRemoved > 0) yet the
        //       window still opens with an unmarked ad tail closed by a bare
        //       return-to-content discontinuity — `adSegmentsRemoved == 0` alone
        //       misses this, so the leading tail leaked. Preferring a brief
        //       over-strip to a played ad is exactly Rule #5.
        if (AdMarkers.containsAds(playlistContent) &&
            (filtered.adSegmentsRemoved == 0 || startsWithUnmarkedAdTail(playlistContent))
        ) {
            return rewriter.filter(playlistContent, assumeStartInAdBreak = true)
        }
        return filtered
    }

    /**
     * True when the playlist opens with an unmarked ad tail: one or more segment
     * URIs appear before the first ad-opening marker (a `stitched` DATERANGE or an
     * #EXT-X-CUE-OUT), and a bare #EXT-X-DISCONTINUITY (a return-to-content
     * boundary) sits between those leading segments and that first opener. That is
     * the signature of a pod whose opener scrolled out of the live window while a
     * fresh pod begins later in the same window. Only considered for playlists
     * already known to contain ads, so clean streams are never touched.
     */
    private fun startsWithUnmarkedAdTail(playlist: String): Boolean {
        var sawLeadingSegment = false
        var sawBareDiscontinuity = false
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXT-X-DATERANGE") && line.contains(AdMarkers.SIGNIFIER, ignoreCase = true) ->
                    return sawLeadingSegment && sawBareDiscontinuity
                line.startsWith("#EXT-X-CUE-OUT") ->
                    return sawLeadingSegment && sawBareDiscontinuity
                line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> {} // header, not a boundary
                line.startsWith("#EXT-X-DISCONTINUITY") -> if (sawLeadingSegment) sawBareDiscontinuity = true
                line.startsWith("#") -> {} // any other tag
                else -> sawLeadingSegment = true // a segment URI
            }
        }
        return false
    }

    /**
     * Drive the on-screen pill from a [filterPlaylist] result. The in-process
     * Android interceptor calls this after each media-playlist refresh because
     * [filterPlaylist] itself stays status-free (it runs on the player's polling
     * thread). Ads stripped sets AD_FILTERED; clean sets AD_BLOCKED.
     */
    fun reportFiltered(filtered: FilteredPlaylist, channel: String? = null) {
        publish(if (filtered.containedAds) AdBlockStatus.AD_FILTERED else AdBlockStatus.AD_BLOCKED, channel)
    }

    /**
     * No ad was shown this refresh: either the playlist was already clean, or a
     * seamless backup player-type swap replaced the ad pod with real content
     * (the good case that avoids the [AdBlockStatus.AD_FILTERED] stall). Green pill.
     */
    fun reportAdBlocked(channel: String? = null) {
        publish(AdBlockStatus.AD_BLOCKED, channel)
    }

    /**
     * Called by the transport layer (e.g. `LocalStreamProxy`) when stream
     * resolution fails BEFORE [resolveCleanStream] can run — typically the GQL
     * playback-token mint threw (a banned/renamed channel, or Twitch changing
     * the API as in GOTCHA #1). Without this the status would sit at
     * [AdBlockStatus.UNKNOWN] ("Checking…") forever, silently masking the
     * outage; moving it to [AdBlockStatus.AD_BLOCK_OFF] makes the failure
     * visible in the on-screen pill instead.
     */
    fun reportResolutionFailure(channel: String? = null) {
        publish(AdBlockStatus.AD_BLOCK_OFF, channel)
    }

    // ---- Strategy 1: Proxy Router ----

    /**
     * SECTION 4.2 — TTV LOL PRO compatible proxy contract:
     *   GET {proxyEndpoint}/{base64(playlistUrl)}
     *   Header: X-Donate-To: https://ttv.lol/donate
     *
     * Only the playlist (≈2KB) goes through the proxy; video segments are
     * still fetched directly from the Twitch CDN — minimal latency & bandwidth
     * impact, and resilient to the proxy going down (falls through to Strategy 2).
     */
    private suspend fun fetchViaProxy(playlistUrl: String): String? = withContext(Dispatchers.IO) {
        val endpoint = config.customProxyEndpoint?.takeIf { it.isNotBlank() } ?: config.proxyEndpoint
        val encoded = playlistUrl.encodeToByteArray().encodeBase64()
        val body = try {
            httpClient.get("$endpoint/$encoded") {
                header("X-Donate-To", "https://ttv.lol/donate")
            }.bodyAsText()
        } catch (e: Exception) {
            return@withContext null
        }
        // Public proxies (ttv.lol etc.) sometimes return Cloudflare challenge
        // pages, 503 HTML, or maintenance pages with a 200 status — VLC then
        // tries to parse HTML as HLS and shows a silent black pane forever.
        // Treat anything that doesn't start with the HLS magic line as a proxy
        // failure so we fall through to the direct-from-Twitch path.
        if (!body.trimStart().startsWith("#EXTM3U")) return@withContext null
        body
    }

    private suspend fun fetchRaw(url: String): String? = withContext(Dispatchers.IO) {
        try {
            httpClient.get(url).bodyAsText()
        } catch (e: Exception) {
            null
        }
    }

    private fun passthrough(url: String, strategy: AdBlockStrategyResult): CleanStreamResult.Success =
        CleanStreamResult.Success(playlistContent = url, variants = emptyList(), strategyUsed = strategy)
}

private const val MAX_CHANNEL_STATUSES = 16

data class AdBlockConfig(
    val strategy: AdBlockStrategy = AdBlockStrategy.PROXY_PRIMARY,
    /**
     * Optional TTV-LOL-PRO-compatible upstream proxy endpoint. Empty by default
     * as of 2026 because every well-known public endpoint
     * (`api.ttv.lol`, `*.luminous.dev`, `lb-*.cdn-perfprod.com`) is either dead,
     * domain-repurposed, Cloudflare-gated, or migrated to a non-HTTP-playlist
     * contract (TTV-LOL-PRO v2 switched to a SOCKS/HTTP-CONNECT proxy).
     *
     * Either run the bundled `proxy-server/` Go binary and point this at it
     * (typically `http://127.0.0.1:8080/playlist`), or rely on the in-process
     * variant-playlist interception in `LocalStreamProxy` (Windows desktop) —
     * which is the path that does the actual ad-segment stripping now.
     */
    val proxyEndpoint: String = "",
    val customProxyEndpoint: String? = null,
    val fallbackToManifestRewrite: Boolean = true,
    val fallbackToBlackFrame: Boolean = true,
)

enum class AdBlockStrategy {
    PROXY_PRIMARY,
    MANIFEST_REWRITE_ONLY,
    DISABLED,
}

/**
 * SECTION 10.3 — drives the on-screen pill: green "AD BLOCKED", yellow
 * "AD FILTERED", red "AD BLOCK OFF". Exposed as a StateFlow so all three
 * UIs (phone, TV, desktop) can render it identically.
 */
enum class AdBlockStatus {
    UNKNOWN,
    AD_BLOCKED,
    AD_FILTERED,
    AD_BLOCK_OFF,
    DISABLED,
}

/**
 * SECTION 09.3 / GOTCHA #2 — tracks proxy reliability so `ProxyHealthWorker`
 * can demote the strategy to manifest-rewrite after 3 consecutive failures
 * (and notify the user), then promote it back once the proxy recovers.
 */
class ProxyHealthTracker(private val unhealthyThreshold: Int = 3) {
    private var consecutiveFailures = 0
    private var consecutiveSuccesses = 0

    // Several channels can resolve at once in multi-stream, on different threads.
    @Synchronized
    fun recordSuccess() {
        consecutiveSuccesses++
        consecutiveFailures = 0
    }

    @Synchronized
    fun recordFailure() {
        consecutiveFailures++
        consecutiveSuccesses = 0
    }

    @Synchronized
    fun isHealthy(): Boolean = consecutiveFailures < unhealthyThreshold

    @Synchronized
    fun shouldNotifyDegradation(): Boolean = consecutiveFailures == unhealthyThreshold
}
