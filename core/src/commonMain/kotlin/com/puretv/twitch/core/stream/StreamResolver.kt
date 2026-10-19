package com.puretv.twitch.core.stream

import com.puretv.twitch.core.api.TwitchConfig
import com.puretv.twitch.core.model.PlaylistVariant
import com.puretv.twitch.core.model.StreamQuality
import com.puretv.twitch.core.model.StreamToken
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlin.random.Random

/**
 * Resolves a Twitch channel login into a playable HLS master-playlist URL,
 * then parses that master playlist into the user-selectable [PlaylistVariant]s
 * described in SECTION 03.4 (Source, 1080p60, 720p60, 480p, 360p, Audio Only).
 */
class StreamResolver(
    private val httpClient: HttpClient,
    private val gqlClient: TwitchGqlClient,
) {
    companion object {
        /**
         * Player type every app uses for the PRIMARY stream. Twitch targets
         * `popout` with far fewer ads than the default `site` web player (the
         * reference userscript forces it for every request), so most streams come
         * back ad-free before the backup swap is ever needed.
         */
        const val PRIMARY_PLAYER_TYPE = "popout"
    }

    suspend fun fetchToken(
        channelLogin: String,
        oauthToken: String? = null,
        playerType: String = "site",
    ): StreamToken = gqlClient.fetchStreamToken(channelLogin, oauthToken, playerType)

    /**
     * Builds the signed usher.ttvnw.net master-playlist URL.
     *
     * GOTCHA #5 (Section 14): the signature/token query params are part of what
     * Twitch validates — never re-encode or reorder them once minted. Anything
     * downstream (proxy, ad-block engine) must treat this URL as opaque and only
     * rewrite the *body* of the playlist it returns, not the request URL.
     */
    fun buildMasterUrl(channelLogin: String, token: StreamToken): String {
        val p = Random.nextInt(100_000, 999_999)
        val encodedToken = token.value.encodeURLParameter()
        return "${TwitchConfig.USHER_BASE}/$channelLogin.m3u8" +
            "?sig=${token.signature}" +
            "&token=$encodedToken" +
            "&allow_source=true" +
            // Adds the audio-only rendition (about 160 kbps). The players never see
            // it unless audio only is asked for: see HlsMasterParser.forPlayback.
            "&allow_audio_only=true" +
            "&allow_spectre=false" +
            "&fast_bread=true" +
            "&p=$p" +
            "&player_backend=mediaplayer" +
            "&playlist_include_framerate=true" +
            "&reassignments_supported=true" +
            "&supported_codecs=avc1" +
            "&transcode_mode=cbr_v1"
    }

    /**
     * One-shot: token -> master URL -> raw master playlist text.
     *
     * The usher fetch is VALIDATED before the result is handed back. The shared
     * Ktor client does not do it for us: `expectSuccess` defaults to false and
     * `CoreModule`'s `HttpResponseValidator` is deliberately scoped to Helix, so
     * a usher rejection arrives as an ordinary response whose body is a JSON
     * error array rather than a playlist (live capture, 2026-09-01:
     * `403 [{"url":"…","error":"Signature is invalid"}]`).
     *
     * Without these checks that body was returned as a SUCCESS — `parseVariants`
     * simply found no `#EXT-X-STREAM-INF` and produced an empty ladder, while
     * `masterUrl` pointed at a URL that can never produce media. The caller then
     * stored it as the playable URL and the player failed on it with nothing to
     * report, which is a black screen with no audio, no error, and a working
     * chat beside it. Fail here instead, naming the status, so the cause reaches
     * the UI and the log.
     */
    suspend fun resolveMasterPlaylist(
        channelLogin: String,
        oauthToken: String? = null,
        playerType: String = "site",
    ): MasterPlaylistResult {
        val token = fetchToken(channelLogin, oauthToken, playerType)
        val url = buildMasterUrl(channelLogin, token)
        val response = httpClient.get(url)
        val raw = response.bodyAsText()

        if (!response.status.isSuccess()) {
            throw UsherPlaylistException(
                "usher refused the master playlist for \"$channelLogin\" (playerType=$playerType): " +
                    "HTTP ${response.status.value} ${response.status.description}. ${raw.summarize()}",
                status = response.status.value,
                body = raw.take(2_000),
                tokenValue = token.value,
            )
        }
        // A 200 is not proof of a playlist: usher answers some rejections with a
        // JSON body and a success status.
        if (!raw.trimStart().startsWith("#EXTM3U")) {
            throw UsherPlaylistException(
                "usher returned HTTP ${response.status.value} for \"$channelLogin\" but the body is not an " +
                    "HLS playlist. ${raw.summarize()}",
                status = response.status.value,
                body = raw.take(2_000),
                tokenValue = token.value,
            )
        }

        return MasterPlaylistResult(masterUrl = url, rawContent = raw, variants = parseVariants(raw))
    }

    /** @see HlsMasterParser.selectVariantUrl */
    fun selectVariantUrl(
        masterPlaylist: String,
        targetResolution: String,
        targetFrameRate: Double,
    ): String? = HlsMasterParser.selectVariantUrl(masterPlaylist, targetResolution, targetFrameRate)

    /** @see HlsMasterParser.parseVariants */
    fun parseVariants(masterPlaylist: String): List<PlaylistVariant> =
        HlsMasterParser.parseVariants(masterPlaylist)
}

/**
 * Pure HLS master-playlist parsing — no network, no collaborators — so the
 * quality-ladder parsing and backup-swap variant matching can be unit-tested
 * directly. [StreamResolver] delegates here.
 */
object HlsMasterParser {

    /**
     * Parses an HLS master playlist (#EXT-X-STREAM-INF / URL pairs) into the
     * quality ladder the UI lets the user pick from.
     */
    fun parseVariants(masterPlaylist: String): List<PlaylistVariant> {
        val variants = mutableListOf<PlaylistVariant>()
        val lines = masterPlaylist.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val attrs = parseAttributeList(line.substringAfter(":"))
                val url = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (url.isNotEmpty() && !url.startsWith("#")) {
                    val resolution = attrs["RESOLUTION"] ?: ""
                    val frameRate = attrs["FRAME-RATE"]?.toDoubleOrNull() ?: 0.0
                    val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L
                    val nameHint = attrs["VIDEO"] ?: resolution
                    variants += PlaylistVariant(
                        quality = StreamQuality.fromVariantName(nameHint),
                        resolution = resolution,
                        frameRate = frameRate,
                        bandwidth = bandwidth,
                        url = url,
                    )
                    i++
                }
            }
            i++
        }
        return variants.sortedBy { it.quality.sortOrder }
    }

    /**
     * Picks the media-playlist URL from [masterPlaylist] that best matches a
     * target quality, so the ad-block backup-swap can fetch the *same* quality
     * from an alternate player type's manifest. Server-side port of vaft's
     * `getStreamUrlForResolution`:
     *   1. exact resolution + frame rate
     *   2. exact resolution, any frame rate
     *   3. closest by pixel count
     *
     * [targetResolution] is the `WIDTHxHEIGHT` string from the primary stream's
     * `#EXT-X-STREAM-INF`, or blank for the audio-only rendition (it has no
     * RESOLUTION). Returns null only if the master has no variant of that kind.
     */
    fun selectVariantUrl(
        masterPlaylist: String,
        targetResolution: String,
        targetFrameRate: Double,
    ): String? {
        // Masters carry an audio-only rendition (allow_audio_only). An audio-only
        // stream matches only that, and a video stream never falls back to it.
        val wantAudio = targetResolution.isBlank()
        val variants = parseVariants(masterPlaylist).filter { (it.quality == StreamQuality.AUDIO_ONLY) == wantAudio }
        if (variants.isEmpty()) return null

        variants.firstOrNull {
            it.resolution == targetResolution && frameRatesMatch(it.frameRate, targetFrameRate)
        }?.let { return it.url }

        variants.firstOrNull { it.resolution == targetResolution }?.let { return it.url }

        val targetPixels = pixelCount(targetResolution)
        return variants.minByOrNull { kotlin.math.abs(pixelCount(it.resolution) - targetPixels) }?.url
    }

    /**
     * The master a player should be handed. Normally that is every rendition
     * except audio only: VLC and ExoPlayer pick renditions by bandwidth, and the
     * 160 kbps audio-only one would look like the cheapest "quality", dropping the
     * picture on a slow link. With [audioOnly] it is the opposite: only the
     * audio-only rendition (and its EXT-X-MEDIA line), so the player downloads no
     * video at all. If the stream has no audio-only rendition, the video master
     * comes back unchanged rather than nothing.
     */
    fun forPlayback(master: String, audioOnly: Boolean): String {
        val lines = master.lines()
        val hasAudioOnly = lines.any { it.startsWith("#EXT-X-STREAM-INF") && isAudioOnlyStreamInf(it) }
        if (!hasAudioOnly) return master
        val out = StringBuilder(master.length)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    val uri = lines.getOrNull(i + 1)
                    val keep = isAudioOnlyStreamInf(line) == audioOnly
                    if (keep) out.append(line).append('\n')
                    if (uri != null && uri.isNotBlank() && !uri.startsWith("#")) {
                        if (keep) out.append(uri).append('\n')
                        i++
                    }
                }
                line.startsWith("#EXT-X-MEDIA") -> {
                    val group = parseAttributeList(line.substringAfter(":"))["GROUP-ID"].orEmpty()
                    if ((group == AUDIO_ONLY_GROUP) == audioOnly) out.append(line).append('\n')
                }
                else -> out.append(line).append('\n')
            }
            i++
        }
        return out.toString().trimEnd('\n') + "\n"
    }

    private const val AUDIO_ONLY_GROUP = "audio_only"

    /** Twitch tags the audio-only rendition `VIDEO="audio_only"`; its CODECS also lack a video codec. */
    private fun isAudioOnlyStreamInf(line: String): Boolean {
        val attrs = parseAttributeList(line.substringAfter(":"))
        if (attrs["VIDEO"] == AUDIO_ONLY_GROUP) return true
        val codecs = attrs["CODECS"] ?: return false
        return VIDEO_CODEC_PREFIXES.none { prefix -> codecs.split(',').any { it.trim().startsWith(prefix) } }
    }

    private val VIDEO_CODEC_PREFIXES = listOf("avc", "hvc", "hev", "av01", "vp09")

    private fun frameRatesMatch(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) < 1.0

    private fun pixelCount(resolution: String): Long {
        val parts = resolution.split('x')
        if (parts.size != 2) return 0
        val w = parts[0].trim().toLongOrNull() ?: return 0
        val h = parts[1].trim().toLongOrNull() ?: return 0
        return w * h
    }

    private fun parseAttributeList(raw: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var i = 0
        var key = StringBuilder()
        var value = StringBuilder()
        var inQuotes = false
        var readingKey = true
        fun flush() {
            if (key.isNotEmpty()) map[key.toString().trim()] = value.toString().trim('"')
            key = StringBuilder(); value = StringBuilder(); readingKey = true
        }
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '"' -> inQuotes = !inQuotes
                c == '=' && readingKey && !inQuotes -> readingKey = false
                c == ',' && !inQuotes -> flush()
                readingKey -> key.append(c)
                else -> value.append(c)
            }
            i++
        }
        flush()
        return map
    }
}

/**
 * The playback token minted, but usher would not hand back a master playlist —
 * a rejected/expired signature, a geo- or subscriber-blackout, or a channel that
 * went offline between the mint and the fetch. Distinct from
 * [GqlPlaybackTokenException], which means the token itself was never issued.
 */
class UsherPlaylistException(
    message: String,
    /** usher's HTTP status, when it answered at all. */
    val status: Int? = null,
    /** The start of usher's body: its JSON error names the reason (see [StreamFailures]). */
    val body: String = "",
    /** The playback token's JSON value, whose `authorization` block can say why too. */
    val tokenValue: String = "",
) : Exception(message)

/** First line of a response body, bounded, for putting in an error message. */
private fun String.summarize(limit: Int = 180): String {
    val firstLine = lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return if (firstLine.length <= limit) firstLine else firstLine.take(limit) + "…"
}

data class MasterPlaylistResult(
    val masterUrl: String,
    val rawContent: String,
    val variants: List<PlaylistVariant>,
)
