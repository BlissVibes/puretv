package com.puretv.twitch.desktop.notify

import com.puretv.twitch.core.model.StreamInfo
import com.puretv.twitch.core.notify.GoLiveAlerts
import com.puretv.twitch.core.notify.GoLiveText
import java.awt.SystemTray
import java.awt.TrayIcon
import javax.swing.SwingUtilities

/**
 * Shows go-live alerts as Windows notifications through the tray icon (AWT's
 * `TrayIcon.displayMessage`, which Windows renders as a toast). The tray icon must
 * be on screen for that, which is why main.kt shows it whenever alerts are on.
 *
 * Compose's own `TrayState.sendNotification` is not used: it drops a notification
 * when the tray has not started collecting yet (the first poll after a
 * `--background` start gets there first) and says nothing about it. Here [deliver]
 * reports whether there was an icon to show on. Whatever could not be shown waits
 * in [pending] and goes out from [flushPending], which main.kt calls once the tray
 * icon exists.
 *
 * Everything from one poll becomes ONE notification (see [GoLiveText.forBatch]);
 * what is still waiting joins the next one, so nothing announced is lost.
 *
 * Windows reports a click on the notification as the tray icon's action, the same
 * as a double-click on the icon, and the action carries no hint which it was. So when
 * the alert was about a single stream, that stream is remembered for [TARGET_TTL_MS]
 * after the toast appears and opens on the first action inside that time. A
 * double-click on the icon just after a toast opens the stream too; the short window
 * keeps that rare.
 */
class DesktopLiveNotifier(
    private val deliver: (title: String, body: String) -> Boolean = ::deliverThroughTray,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Target(val login: String, val atMs: Long)

    private val lock = Any()
    private var pending: List<StreamInfo> = emptyList()
    private var pendingSinceMs = 0L
    @Volatile private var recentTarget: Target? = null

    /** The channel open in the window right now (kept current by App); never toasted. */
    @Volatile var watchingLogin: String? = null

    /** More channels on screen at once (multi-stream tiles, lower case); never toasted either. */
    @Volatile var alsoWatching: Set<String> = emptySet()

    private fun notWatched(streams: List<StreamInfo>): List<StreamInfo> {
        val also = alsoWatching
        return if (also.isEmpty()) streams else streams.filterNot { it.userLogin.lowercase() in also }
    }

    fun show(streams: List<StreamInfo>) {
        val fresh = notWatched(GoLiveAlerts.exceptWatching(streams, watchingLogin))
        if (fresh.isEmpty()) return
        synchronized(lock) {
            if (pending.isEmpty()) pendingSinceMs = clock()
            val known = pending.mapTo(HashSet()) { it.userLogin.lowercase() }
            pending = pending + fresh.filter { it.userLogin.lowercase() !in known }
            flushLocked()
        }
    }

    /** Shows what is waiting; called when the tray icon has just been added. */
    fun flushPending() {
        synchronized(lock) { flushLocked() }
    }

    private fun flushLocked() {
        if (pending.isEmpty()) return
        if (clock() - pendingSinceMs > PENDING_TTL_MS) {
            pending = emptyList()
            return
        }
        // The viewer may have opened one of them since it was queued.
        val shown = notWatched(GoLiveAlerts.exceptWatching(pending, watchingLogin))
        if (shown.isEmpty()) {
            pending = emptyList()
            return
        }
        val message = GoLiveText.forBatch(shown)
        if (deliver(message.title, message.body)) {
            pending = emptyList()
            recentTarget = shown.singleOrNull()?.let { Target(it.userLogin, clock()) }
        }
    }

    /** The stream the last alert was about, once, if it was recent and about one stream. */
    fun consumeRecentTarget(): String? {
        val target = recentTarget ?: return null
        recentTarget = null
        return target.login.takeIf { clock() - target.atMs < TARGET_TTL_MS }
    }

    companion object {
        /** Long enough to read a toast and click it, short enough that a later double-click is just a double-click. */
        const val TARGET_TTL_MS = 15_000L

        /** An alert that could not be shown for this long (no tray) is old news. */
        const val PENDING_TTL_MS = 10 * 60_000L

        private fun deliverThroughTray(title: String, body: String): Boolean {
            if (!SystemTray.isSupported()) return false
            val icon = runCatching { SystemTray.getSystemTray().trayIcons.firstOrNull() }.getOrNull() ?: return false
            SwingUtilities.invokeLater { runCatching { icon.displayMessage(title, body, TrayIcon.MessageType.INFO) } }
            return true
        }
    }
}
