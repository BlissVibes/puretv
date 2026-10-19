package com.puretv.twitch.tv.player

import android.content.Context
import android.os.Looper
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.puretv.twitch.core.adblock.AdBlockEngine
import okhttp3.Protocol
import io.ktor.client.HttpClient
import com.puretv.twitch.core.stream.PlayableStreamCache
import com.puretv.twitch.core.adblock.MediaPlaylistCleaner
import com.puretv.twitch.core.adblock.AdSimulator
import com.puretv.twitch.core.adblock.BackupStreamResolver
import com.puretv.twitch.core.adblock.PlaylistAction
import com.puretv.twitch.core.adblock.PlaylistDetect
import com.puretv.twitch.core.model.StreamQuality
import com.puretv.twitch.core.stream.HlsMasterParser
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap

/**
 * SECTION 06.3 / 07.4, TV counterpart of the phone app's `TwitchPlayer`.
 * Identical ExoPlayer/OkHttp/[AdBlockEngine] wiring (low-latency live HLS + the
 * seamless [TvAdBlockInterceptor] backup-swap pipeline), duplicated rather than
 * shared because `ExoPlayer` is an Android-only `androidx.media3` type that
 * can't live in the multiplatform `core` module (Section 12.2: app-tv shares no
 * platform/UI code with app-android, only `core`).
 */
@UnstableApi
class TvPlayer(
    private val context: Context,
    adBlockEngine: AdBlockEngine,
    backupStreamResolver: BackupStreamResolver,
    httpClient: HttpClient,
    playableStreamCache: PlayableStreamCache,
) {
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            TvAdBlockInterceptor(
                adBlockEngine = adBlockEngine,
                cleaner = MediaPlaylistCleaner(
                    adBlockEngine = adBlockEngine,
                    backupStreamResolver = backupStreamResolver,
                    httpClient = httpClient,
                    nowMs = System::currentTimeMillis,
                    // ExoPlayer cannot play an empty window (issue #22); wait for content instead.
                    holdEmptyWindows = true,
                ),
                playableStreamCache = playableStreamCache,
                audioOnly = { audioOnly },
            ),
        )
        .build()

    /**
     * Called when the first video frame of a stream is on screen. Feeds the local
     * time-to-first-frame mark; the stream view model sets and clears it.
     */
    @Volatile var onFirstFrame: (() -> Unit)? = null

    /**
     * Backing player. Held lazily and nulled on [release] so this app-wide
     * singleton can free its codec/audio resources when the Activity finishes yet
     * still hand back a FRESH player if the process survives and the user reopens
     * the app: accessing [exoPlayer] after a release rebuilds it rather than
     * handing out a released (unusable) instance.
     */
    private var _exoPlayer: ExoPlayer? = null

    val exoPlayer: ExoPlayer
        get() = _exoPlayer ?: buildPlayer().also { _exoPlayer = it }

    private fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context)
                .setDataSourceFactory(OkHttpDataSource.Factory(okHttpClient)),
        )
        // Request audio focus so another media app / a phone call ducks or pauses
        // us instead of being talked over, and pause when audio output goes away.
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setLoadControl(
            DefaultLoadControl.Builder()
                // Deeper buffer than a thin low-latency profile: Fire TV Sticks and
                // Chromecast dongles jitter on Wi-Fi and a thin buffer causes chronic
                // rebuffering, which reads worse on a TV than a little extra latency.
                .setBufferDurationsMs(
                    /* minBuffer  */ 5_000,
                    /* maxBuffer  */ 30_000,
                    /* playback   */ 1_500,
                    /* rebuffer   */ 3_000,
                )
                .build(),
        )
        .setLivePlaybackSpeedControl(
            DefaultLivePlaybackSpeedControl.Builder()
                .setFallbackMaxPlaybackSpeed(1.04f)
                .build(),
        )
        .build()
        .apply {
            playWhenReady = true
            addListener(recoveryListener)
        }

    /**
     * Bounded retry counter for transient HLS errors (see [recoveryListener]).
     * Reset to 0 on the first STATE_READY AND whenever a new stream is selected
     * via [playUrl], so a healthy stream never inherits a dead sibling stream's
     * exhausted budget on the shared singleton.
     */
    private var errorRetries = 0

    /**
     * Called on the main thread once the backed-off retries are spent. The stream
     * view model answers with a fresh stream session (the old signed URL may have
     * expired, for example after a long pause) or, failing that, a visible error,
     * so playback never just stays black.
     */
    @Volatile var onGaveUp: ((PlaybackException) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable { _exoPlayer?.prepare() }

    /**
     * SECTION 06.3 keeps live HLS blips from dead-ending on a black screen.
     * BEHIND_LIVE_WINDOW (we fell off the live edge) is recovered by re-seeking
     * to the default (live) position and re-preparing. Any other error is retried
     * with growing pauses ([RETRY_DELAYS_MS]), so a brief network drop gets time
     * to clear instead of three instant retries that all hit the same outage;
     * once those are spent, [onGaveUp] takes over. Reaching STATE_READY resets
     * the budget.
     */
    private val recoveryListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                exoPlayer.seekToDefaultPosition()
                exoPlayer.prepare()
                return
            }
            if (errorRetries < RETRY_DELAYS_MS.size) {
                mainHandler.removeCallbacks(retryRunnable)
                mainHandler.postDelayed(retryRunnable, RETRY_DELAYS_MS[errorRetries])
                errorRetries++
            } else {
                onGaveUp?.invoke(error)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) errorRetries = 0
        }

        override fun onRenderedFirstFrame() {
            onFirstFrame?.invoke()
        }
    }

    /**
     * Selects a stream for playback, resetting the transient-error retry budget so
     * a fresh stream always gets its full allowance even if the previous one
     * exhausted it without ever reaching STATE_READY (a dead/offline channel).
     */
    fun playUrl(url: String) {
        errorRetries = 0
        mainHandler.removeCallbacks(retryRunnable)
        with(exoPlayer) {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            play()
        }
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    }

    /**
     * Read by the playlist interceptor on OkHttp threads: while true it hands
     * ExoPlayer the audio-only master, so no video is downloaded at all.
     */
    @Volatile private var audioOnly = false

    /**
     * SECTION 7.4: pick a quality on the live HLS ladder (FAST_FORWARD/REWIND and
     * the quality chips drive this). AUTO lets ABR pick what the link sustains;
     * SOURCE always plays the top rendition; a specific resolution caps the video
     * size; AUDIO_ONLY reloads the stream as audio only. Every branch resets what
     * the others set, because this singleton carries its parameters between streams.
     */
    fun setQuality(quality: StreamQuality) {
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .setForceHighestSupportedBitrate(quality == StreamQuality.SOURCE)
        when (quality) {
            StreamQuality.SOURCE, StreamQuality.AUTO, StreamQuality.AUDIO_ONLY -> builder.clearVideoSizeConstraints()
            StreamQuality.P1080P60 -> builder.setMaxVideoSize(1920, 1080)
            StreamQuality.P720P60 -> builder.setMaxVideoSize(1280, 720)
            StreamQuality.P480P -> builder.setMaxVideoSize(854, 480)
            StreamQuality.P360P -> builder.setMaxVideoSize(640, 360)
        }
        exoPlayer.trackSelectionParameters = builder.build()
        val wantAudioOnly = quality == StreamQuality.AUDIO_ONLY
        if (wantAudioOnly != audioOnly) {
            audioOnly = wantAudioOnly
            reloadCurrentStream()
        }
    }

    /**
     * Loads the current stream again from its master, which the interceptor now
     * serves as audio only or as video, and carries on at the live edge.
     */
    private fun reloadCurrentStream() {
        val player = _exoPlayer ?: return
        val item = player.currentMediaItem ?: return
        mainHandler.removeCallbacks(retryRunnable)
        player.setMediaItem(item)
        player.prepare()
    }

    /**
     * Stops playback and clears the queue without tearing down the player. No-op if
     * already released, so a stream-leave teardown after the Activity finished does
     * not resurrect a fresh idle player via [exoPlayer].
     */
    fun stop() {
        mainHandler.removeCallbacks(retryRunnable)
        _exoPlayer?.run {
            stop()
            clearMediaItems()
        }
    }

    /**
     * Frees the underlying player. Safe from Activity.onDestroy: the next
     * [exoPlayer] access rebuilds a fresh instance, so a surviving-process relaunch
     * never touches a released player.
     */
    fun release() {
        mainHandler.removeCallbacks(retryRunnable)
        _exoPlayer?.release()
        _exoPlayer = null
        errorRetries = 0
    }

    private companion object {
        /** Pauses before each automatic retry: 1s, then 3s, then 6s. */
        val RETRY_DELAYS_MS = longArrayOf(1_000, 3_000, 6_000)
    }
}

/**
 * Removes Twitch ad pods from HLS playlist responses. The decision is made from
 * the RESPONSE, not the URL: obvious media segments (.ts/.m4s/.mp4/.aac) pass
 * through untouched and unbuffered; everything else is peeked, and any HLS
 * playlist (Content-Type mpegurl, or a body starting with #EXTM3U) is handled.
 *
 * The ad-blocking rules live in core's [MediaPlaylistCleaner], shared by all
 * three apps: the backup player-type swap first (seamless, green pill), then
 * stripping (yellow pill), holding an all-ad window instead of handing ExoPlayer
 * an empty playlist (issue #22), and keeping the timeline continuous across
 * swaps. This class only adapts OkHttp to it.
 *
 * The backup swap needs the channel + the ad-bearing variant's quality; both
 * are learned from the master playlist as it passes through (the master request
 * URL carries the channel; its #EXT-X-STREAM-INF lines carry each variant's
 * resolution/frame-rate), keyed by the variant URL path so the media refreshes
 * that follow can look themselves up.
 */
@UnstableApi
class TvAdBlockInterceptor(
    private val adBlockEngine: AdBlockEngine,
    private val cleaner: MediaPlaylistCleaner,
    private val playableStreamCache: PlayableStreamCache,
    /** True while the player should get the audio-only rendition instead of video. */
    private val audioOnly: () -> Boolean,
) : Interceptor {

    // Learned from the master. channelLogin is @Volatile (written on the master
    // fetch, read on media fetches, possibly different OkHttp threads); the map is
    // concurrent for the same reason. Keyed by the variant media-playlist URL path.
    @Volatile private var channelLogin: String? = null
    private val variantByPath = ConcurrentHashMap<String, MediaPlaylistCleaner.VariantQuality>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // The view model resolved (and validated) this exact master moments ago:
        // serve that copy instead of asking usher a second time. A master carries
        // no ad segments, so this is safe whether ad blocking is on or off.
        playableStreamCache.masterBody(request.url.toString())?.let { master ->
            learnFromMaster(request.url, master)
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(HlsMasterParser.forPlayback(master, audioOnly()).toResponseBody(HLS_MEDIA_TYPE))
                .build()
        }

        if (PlaylistDetect.classifyRequest(request.url.encodedPath) == PlaylistAction.SKIP_SEGMENT) {
            return chain.proceed(request)
        }

        val response = chain.proceed(request)
        val contentType = response.header("Content-Type")
        val firstLine = if ("mpegurl" in (contentType?.lowercase() ?: "")) {
            "#EXTM3U"
        } else {
            runCatching { response.peekBody(8L * 1024).string() }
                .getOrNull()
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
        }

        if (PlaylistDetect.classifyResponse(contentType, firstLine) != PlaylistAction.FILTER_PLAYLIST) {
            return response
        }

        val body = response.body ?: return response
        val rawBody = body.string()

        // A master playlist (#EXT-X-STREAM-INF) carries no ad segments; we only
        // learn its channel + variant ladder, then hand the player the renditions
        // it should see (never the audio-only one by accident, see forPlayback).
        // This runs with ad blocking off too.
        if (rawBody.contains("#EXT-X-STREAM-INF")) {
            learnFromMaster(request.url, rawBody)
            return response.newBuilder()
                .body(HlsMasterParser.forPlayback(rawBody, audioOnly()).toResponseBody(HLS_MEDIA_TYPE))
                .removeHeader("Content-Length")
                .build()
        }

        // Honour the user's "Enable ad block" switch: when off, media playlists
        // pass through unfiltered (and the engine has already flipped the pill to
        // DISABLED).
        if (!adBlockEngine.isEnabled) {
            return response.newBuilder()
                .body(rawBody.toResponseBody(HLS_MEDIA_TYPE))
                .removeHeader("Content-Length")
                .build()
        }

        // Media playlist: AdSimulator only makes sense here (a media playlist with
        // #EXTINF/#EXT-X-MEDIA-SEQUENCE), not on the master.
        val raw = AdSimulator.inject(rawBody)
        val cleaned = runBlocking {
            cleaner.clean(
                variantUrl = request.url.toString(),
                raw = raw,
                channelLogin = channelLogin,
                quality = variantByPath[request.url.encodedPath],
                isCancelled = { chain.call().isCanceled() },
            )
        }

        return response.newBuilder()
            .body(cleaned.content.toResponseBody(HLS_MEDIA_TYPE))
            .removeHeader("Content-Length")
            .build()
    }

    /** Record the channel + per-variant quality so media refreshes can swap. */
    private fun learnFromMaster(masterUrl: HttpUrl, master: String) {
        // Usher master URL: .../api/channel/hls/{channel}.m3u8
        val channel = masterUrl.pathSegments.lastOrNull()
            ?.removeSuffix(".m3u8")
            ?.takeIf { it.isNotBlank() }
        if (channel != null && channel != channelLogin) {
            // New stream: drop the previous channel's variant map so it can't
            // grow unbounded across a channel-surfing session.
            variantByPath.clear()
            channelLogin = channel
        }
        runCatching { HlsMasterParser.parseVariants(master) }.getOrNull()?.forEach { variant ->
            variant.url.toHttpUrlOrNull()?.let { url ->
                variantByPath[url.encodedPath] = MediaPlaylistCleaner.VariantQuality(variant.resolution, variant.frameRate)
            }
        }
    }

    private companion object {
        val HLS_MEDIA_TYPE = "application/vnd.apple.mpegurl".toMediaType()
    }
}
