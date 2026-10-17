package com.puretv.twitch.android.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.puretv.twitch.android.MainActivity
import com.puretv.twitch.android.R
import org.koin.android.ext.android.inject

/**
 * Issue #27: background play. A media session around the app's one ExoPlayer
 * (owned by [TwitchPlayer], so the ad-block interceptor stays in the pipeline and
 * no second player is ever built). Media3 turns it into a foreground service with
 * a media notification while audio plays, and the system mirrors that session on
 * the lock screen, in Bluetooth controls and in the PiP window.
 *
 * Started and stopped only through [BackgroundPlayback]. The session lives exactly
 * as long as the service, so a player rebuilt after [TwitchPlayer.release] is
 * picked up by the next start rather than a stale one being kept.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val twitchPlayer: TwitchPlayer by inject()
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_launcher_monochrome)
            },
        )
        val session = MediaSession.Builder(this, twitchPlayer.exoPlayer)
            .setSessionActivity(openAppIntent())
            .setCallback(SessionCallback())
            .setCustomLayout(listOf(stopButton))
            .build()
        mediaSession = session
        // Nothing binds to this service in-process (it is started, not bound), so
        // hand the session to the notification manager directly instead of
        // waiting for an onGetSession that may never come.
        addSession(session)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Defensive: if the shared player was rebuilt while this service lived,
        // follow it so the notification never drives a released instance.
        mediaSession?.let { session ->
            val current = twitchPlayer.exoPlayer
            if (session.player !== current) session.player = current
        }
        // Not sticky: after a process death there is no stream to resume, and a
        // restarted service would only hold an empty session.
        return START_NOT_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * Swiping PureTV away from recents ends playback. Media3's default keeps a
     * playing service alive, which would leave audio running for an app the viewer
     * just dismissed. Closing the PiP window removes the task on stock Android
     * too, so it lands here as well and is treated the same way.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        twitchPlayer.stop()
        stopSelf()
    }

    override fun onDestroy() {
        // Release the session only. The player belongs to TwitchPlayer, which
        // outlives this service, so it is deliberately not released here.
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    /** Tapping the notification brings the existing task (and the stream) back, like the launcher icon. */
    private fun openAppIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            // Live has nothing to skip to, so drop the previous/next buttons the
            // notification would otherwise draw, and grant the Stop button.
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .removeAll(
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                )
                .build()
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(stopCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(playerCommands)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_STOP) {
                // Stop, not clear: the stream screen is still on the back stack,
                // so coming back offers play instead of an empty player.
                twitchPlayer.stopKeepingStream()
                stopSelf()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    private companion object {
        const val ACTION_STOP = "com.puretv.twitch.android.STOP_PLAYBACK"
        val stopCommand = SessionCommand(ACTION_STOP, Bundle.EMPTY)
        val stopButton: CommandButton = CommandButton.Builder(CommandButton.ICON_STOP)
            .setDisplayName("Stop")
            .setSessionCommand(stopCommand)
            .build()
    }
}
