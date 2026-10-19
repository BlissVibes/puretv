package com.puretv.twitch.desktop.notify

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import com.puretv.twitch.core.model.StreamInfo
import com.puretv.twitch.core.notify.GoLiveText

/**
 * Shows go-live alerts as Windows notifications through the tray icon (AWT's
 * `TrayIcon.displayMessage`, which Windows renders as a toast). The tray icon must
 * be on screen for that, which is why main.kt shows it whenever alerts are on.
 *
 * Everything from one poll becomes ONE notification (see [GoLiveText.forBatch]):
 * Compose hands notifications to the tray over a rendezvous channel, so a second
 * one sent before the first is picked up would be dropped, and one alert per poll
 * is kinder anyway.
 *
 * Windows reports a click on the notification as the tray icon's action, the same
 * as a double-click on the icon. When the alert was about a single stream, that
 * stream is remembered for [TARGET_TTL_MS] so the click can open it.
 */
class DesktopLiveNotifier(
    private val trayState: TrayState,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Target(val login: String, val atMs: Long)

    @Volatile private var recentTarget: Target? = null

    fun show(streams: List<StreamInfo>) {
        if (streams.isEmpty()) return
        val message = GoLiveText.forBatch(streams)
        recentTarget = streams.singleOrNull()?.let { Target(it.userLogin, clock()) }
        trayState.sendNotification(Notification(message.title, message.body, Notification.Type.Info))
    }

    /** The stream the last alert was about, once, if it was recent and about one stream. */
    fun consumeRecentTarget(): String? {
        val target = recentTarget ?: return null
        recentTarget = null
        return target.login.takeIf { clock() - target.atMs < TARGET_TTL_MS }
    }

    companion object {
        const val TARGET_TTL_MS = 2 * 60_000L
    }
}
