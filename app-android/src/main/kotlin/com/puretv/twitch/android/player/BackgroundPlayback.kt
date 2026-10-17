package com.puretv.twitch.android.player

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/**
 * Issue #27: keep a stream playing after the viewer leaves the app (another app
 * full screen, the screen off) instead of pausing it. This is the one place that
 * decides what leaving and coming back do to playback, so [PlayerSurface],
 * `MainActivity` and `StreamViewModel` all follow the same rules.
 *
 * With [enabled] on, a [PlaybackService] holds a media session around the shared
 * ExoPlayer for as long as a stream is loaded. Media3 promotes it to a foreground
 * service with a media notification while audio plays, which is what lets the
 * process keep streaming in the background and puts controls on the lock screen.
 * With it off, nothing starts and leaving pauses, exactly as before.
 *
 * PiP is unchanged: the auto-PiP on Home still floats the video, and the service
 * just rides along (the PiP window also picks up the session's play/pause).
 */
@OptIn(UnstableApi::class)
class BackgroundPlayback(
    private val context: Context,
    private val twitchPlayer: TwitchPlayer,
) {
    /** Mirrors the "Background play" setting; fed by `PureTvApp`. */
    @Volatile
    var enabled: Boolean = true
        private set

    /**
     * Set when leaving the app paused a playing stream, so returning resumes it.
     * Anything else that paused (the viewer, the notification, a phone call, a
     * headset unplug) is left alone: an unconditional resume would undo a pause
     * the viewer made from the lock screen the moment they unlock.
     */
    private var resumeOnReturn = false

    /**
     * Mirrors the "Picture-in-picture on leave" setting; fed by `PureTvApp`. Off
     * means Home goes straight to background play. Closing a PiP window usually
     * removes the whole task on stock Android, which ends playback, so without
     * this a viewer could never get audio-only playback while using another app.
     */
    @Volatile
    var pipOnLeave: Boolean = true
        private set

    fun setPipOnLeave(enabled: Boolean) {
        pipOnLeave = enabled
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        // Turning it off mid-stream drops the notification; the next leave pauses.
        if (!enabled) stopService()
    }

    /**
     * Brings the media session up for the loaded stream. Must be called while the
     * app is in the foreground: Android 12+ refuses to start a foreground service
     * from the background, which is why this is not deferred until the viewer
     * actually leaves. Idempotent, so every resume can call it.
     */
    fun startService() {
        if (!enabled || twitchPlayer.exoPlayer.mediaItemCount == 0) return
        runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
            .onFailure { Log.w(TAG, "Could not start background playback", it) }
    }

    /** In-app Back left the stream: no notification, and nothing to resume later. */
    fun onStreamClosed() {
        resumeOnReturn = false
        stopService()
    }

    /** Tears the session and its notification down. Safe if it is not running. */
    fun stopService() {
        runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
    }

    /**
     * The Activity stopped (another app, Home without PiP, screen off). With
     * background play on the stream keeps going; otherwise pause, as before.
     * [inPip] keeps today's rule that a visible PiP window is never paused.
     */
    fun onAppStopped(inPip: Boolean) {
        if (shouldPauseOnLeave(backgroundPlay = enabled, inPip = inPip)) pauseForLeave()
    }

    /**
     * The viewer closed the PiP window and the Activity survived it. With
     * background play on, audio carries on under the notification, which has a
     * Stop, so nothing is orphaned: closing the window is how you trade the video
     * for audio only. With it off, closing the window pauses. The Activity can
     * still report PiP while it stops, so [onAppStopped] alone would let it play
     * on with no controls anywhere.
     *
     * Stock Android usually removes the whole task when the PiP window closes.
     * That never reaches here: it lands in `PlaybackService.onTaskRemoved` and a
     * finishing `MainActivity.onDestroy`, and ends playback like swiping the app
     * away from recents, whatever this setting says.
     */
    fun onPipDismissed() {
        if (shouldPauseOnLeave(backgroundPlay = enabled, inPip = false)) pauseForLeave()
    }

    /** The Activity is back in front: undo our own pause, then (re)start the session. */
    fun onAppResumed() {
        val player = twitchPlayer.exoPlayer
        if (resumeOnReturn) {
            resumeOnReturn = false
            if (player.mediaItemCount > 0) player.play()
        }
        startService()
    }

    private fun pauseForLeave() {
        val player = twitchPlayer.exoPlayer
        if (player.playWhenReady) {
            player.pause()
            resumeOnReturn = true
        }
    }

    internal companion object {
        private const val TAG = "BackgroundPlayback"

        /** Leaving pauses only when background play is off and no PiP window shows the video. */
        fun shouldPauseOnLeave(backgroundPlay: Boolean, inPip: Boolean): Boolean = !backgroundPlay && !inPip
    }
}
