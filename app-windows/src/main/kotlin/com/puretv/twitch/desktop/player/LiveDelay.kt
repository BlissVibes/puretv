package com.puretv.twitch.desktop.player

import com.puretv.twitch.core.stream.HlsLiveTiming
import java.util.concurrent.ConcurrentHashMap

/** The monotonic clock the stream delay is measured against, in milliseconds. */
fun monoNowMs(): Long = System.nanoTime() / 1_000_000

/**
 * What the local proxy last handed the player for each channel: the playlist's timeline,
 * and what its fetches say about Twitch's clock. The proxy writes, players read.
 *
 * Twitch's clock is used rather than this computer's, which can be many seconds off
 * (13 s behind on the PC this was written on). Two things give it away:
 *  - the HTTP `Date` of each playlist response, in whole seconds: the clock read between
 *    that second and the next when the response left;
 *  - the playlist's own `X-SERVER-TIME`, to the hundredth, but stamped once when the
 *    session was made, so it only says the clock is at least that.
 * Each fetch narrows the range the clock can be in; the estimate is its middle, good to
 * about half a second at first and better as fetches land at different parts of a second.
 */
object LiveTimingBoard {
    data class Served(val timing: HlsLiveTiming, val atMonoMs: Long)

    private class Channel {
        @Volatile var first: Served? = null

        // Twitch's clock less the monotonic clock is between these.
        var lowMs: Long? = null
        var highMs: Long? = null
    }

    private val channels = ConcurrentHashMap<String, Channel>()

    private fun key(channel: String) = channel.trim().lowercase()

    /** A player asked for the channel's master playlist: what it is served next is where it starts. */
    fun onMasterRequested(channel: String) {
        if (channel.isBlank()) return
        channels.getOrPut(key(channel)) { Channel() }.first = null
    }

    /**
     * The proxy served [playlist] for [channel] at [atMonoMs]. [httpDateMs] is the `Date`
     * header of Twitch's response, when it had one.
     */
    fun onVariantServed(channel: String, playlist: String, atMonoMs: Long = monoNowMs(), httpDateMs: Long? = null) {
        if (channel.isBlank()) return
        val timing = HlsLiveTiming.parse(playlist) ?: return
        val c = channels.getOrPut(key(channel)) { Channel() }
        if (c.first == null) c.first = Served(timing, atMonoMs)
        synchronized(c) {
            // Neither stamp can be later than the moment it arrived here.
            listOfNotNull(timing.serverTimeMs, httpDateMs).forEach { stamp ->
                val low = stamp - atMonoMs
                if (c.lowMs.let { it == null || low > it }) c.lowMs = low
            }
            if (httpDateMs != null) {
                // The response left before the next whole second, and took at most the trip to arrive.
                val high = httpDateMs + 1000 + TRIP_MS - atMonoMs
                if (c.highMs.let { it == null || high < it }) c.highMs = high
            }
        }
    }

    /** The first playlist served since the player last asked for the master playlist. */
    fun first(channel: String): Served? = channels[key(channel)]?.first

    /** Twitch's clock less the monotonic clock, once a fetch has given a reading of it. */
    fun serverMinusMonoMs(channel: String): Long? {
        val c = channels[key(channel)] ?: return null
        return synchronized(c) {
            val low = c.lowMs ?: return@synchronized null
            val high = c.highMs
            if (high == null || high < low) low else low + (high - low) / 2
        }
    }

    fun forget(channel: String) { channels.remove(key(channel)) }

    /** Allowance for a playlist response's trip from Twitch to here. */
    private const val TRIP_MS = 150L
}

/**
 * Follows one live playback and says how far behind live its picture is.
 *
 * libVLC gives no running clock for a live stream (one report of 0, then nothing), so
 * this counts from the first picture: that picture is the start of the segment VLC
 * begins at ([HlsLiveTiming.vlcStartMs]), and from then on the picture moves at the
 * speed of the clock. While the picture stands still (a pause, a rebuffer) the clock is
 * held, and when it moves again the time it stood is added to the delay.
 *
 * The delay does not change while the stream plays, and does not change while it is
 * held: it steps up once, when the picture moves again.
 */
class LiveDelayTracker(private val liveDelayMs: Long) {
    /** Broadcast time of the picture, less the monotonic clock. Null until [start], or when lost. */
    private var pictureMinusMono: Long? = null
    private var heldAt: Long? = null

    /** The first picture is on screen at [monoMs]. False when [first] cannot say where VLC began. */
    fun start(first: HlsLiveTiming, monoMs: Long): Boolean {
        val begin = first.vlcStartMs(liveDelayMs) ?: return false
        pictureMinusMono = begin - monoMs
        heldAt = null
        return true
    }

    /** The picture stopped moving at [monoMs]. */
    fun hold(monoMs: Long) {
        if (pictureMinusMono != null && heldAt == null) heldAt = monoMs
    }

    /**
     * The picture moves again at [monoMs]. After a long hold the player may have skipped
     * ahead to what the playlist still lists, so where the picture is can no longer be
     * told: the delay is dropped until the next start.
     */
    fun release(monoMs: Long) {
        val since = heldAt ?: return
        heldAt = null
        val stood = monoMs - since
        pictureMinusMono = if (stood > MAX_HOLD_MS) null else pictureMinusMono?.minus(stood)
    }

    /** Milliseconds behind live, given Twitch's clock less the monotonic clock. Null when not known. */
    fun delayMs(serverMinusMonoMs: Long): Long? =
        pictureMinusMono?.let { (serverMinusMonoMs - it).coerceAtLeast(0) }

    private companion object {
        /** Twitch lists about 30 s of segments; a picture held longer than this may not resume where it stood. */
        const val MAX_HOLD_MS = 20_000L
    }
}

/** A stream delay for the player bar: seconds with two decimals, "12.34s". */
fun formatLiveDelay(delayMs: Long): String {
    val hundredths = (delayMs.coerceAtLeast(0) + 5) / 10
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}s"
}
