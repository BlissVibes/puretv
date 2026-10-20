package com.puretv.twitch.android.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.puretv.twitch.android.MainActivity
import com.puretv.twitch.android.R
import com.puretv.twitch.core.model.StreamInfo
import com.puretv.twitch.core.notify.GoLiveAlerts
import com.puretv.twitch.core.notify.GoLiveText

/**
 * Posts go-live alerts as system notifications, on their own "Channels going live"
 * channel so the viewer can silence them in Android settings without touching the
 * background-play controls.
 *
 * Up to [GoLiveAlerts.MAX_SEPARATE] streams from one poll get a notification each,
 * tapping one opens that stream. More than that is one summary listing them, which
 * opens Following: four notifications landing at once is a wall, not news.
 *
 * Fed by the shared LiveFollowsMonitor's callback, so whichever poll saw the
 * go-live (the in-app timer, the background worker, Home or Following refreshing)
 * the alert is posted exactly once.
 */
@OptIn(UnstableApi::class)
class GoLiveNotifications(private val context: Context) {

    /**
     * The channel open on the stream screen right now (kept current by MainActivity),
     * or null. Nobody needs telling that the stream they are watching is live.
     */
    @Volatile
    var watchingLogin: String? = null

    fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName("Channels going live")
            .setDescription("A channel you follow on Twitch just started streaming.")
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** True when Android will actually show what we post (permission granted, app not blocked). */
    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun announce(streams: List<StreamInfo>) {
        val fresh = GoLiveAlerts.exceptWatching(streams, watchingLogin)
        if (fresh.isEmpty() || !canPost()) return
        ensureChannel()
        if (fresh.size > GoLiveAlerts.MAX_SEPARATE) postSummary(fresh) else fresh.forEach(::postOne)
    }

    // canPost() above checked POST_NOTIFICATIONS before either of these runs.
    @SuppressLint("MissingPermission")
    private fun postOne(stream: StreamInfo) {
        val login = stream.userLogin.lowercase()
        val message = GoLiveText.forStream(stream)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(message.title)
            .setContentText(message.body)
            .setContentIntent(openIntent(requestCode = login.hashCode()) { putExtra(EXTRA_OPEN_CHANNEL, login) })
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, login.hashCode(), notification) }
    }

    @SuppressLint("MissingPermission")
    private fun postSummary(streams: List<StreamInfo>) {
        val message = GoLiveText.forBatch(streams)
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(message.title)
        streams.take(INBOX_LINES).forEach { stream ->
            inbox.addLine("${GoLiveText.name(stream)}: ${GoLiveText.forStream(stream).body}")
        }
        if (streams.size > INBOX_LINES) inbox.setSummaryText("and ${streams.size - INBOX_LINES} more")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(message.title)
            .setContentText(message.body)
            .setStyle(inbox)
            .setNumber(streams.size)
            .setContentIntent(openIntent(requestCode = 0) { putExtra(EXTRA_OPEN_FOLLOWING, true) })
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(SUMMARY_TAG, 0, notification) }
    }

    /**
     * An explicit intent at MainActivity (singleTop, so a running app gets it in
     * onNewIntent instead of a second copy). One request code per channel, so each
     * notification keeps its own target.
     */
    private fun openIntent(requestCode: Int, extras: Intent.() -> Unit): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply(extras)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_ID = "go_live"
        const val EXTRA_OPEN_CHANNEL = "com.puretv.twitch.android.extra.OPEN_CHANNEL"
        const val EXTRA_OPEN_FOLLOWING = "com.puretv.twitch.android.extra.OPEN_FOLLOWING"
        private const val GROUP_KEY = "go_live"
        private const val NOTIFICATION_TAG = "go_live"
        private const val SUMMARY_TAG = "go_live_summary"
        private const val INBOX_LINES = 5
    }
}
