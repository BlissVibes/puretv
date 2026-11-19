package com.puretv.twitch.desktop.platform

/**
 * Restarts PureTV from inside the app, for settings that only apply when it starts
 * (the playback engine). [restart] quits the normal way; main() starts the launcher
 * again right before the process exits ([relaunchIfRequested]). The new copy finds
 * the old one still closing and waits for it (see SingleInstance), so the two never
 * run together.
 *
 * Only in an installed app: a dev run has no launcher to start, so [isAvailable] is
 * false there and the UI keeps its "Applies after restart" note instead.
 */
object AppRestart {
    /** The installed launcher, set by jpackage; null in a dev run. */
    private val launcher: String?
        get() = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }

    val isAvailable: Boolean get() = launcher != null

    @Volatile private var requested = false

    // The app's normal quit, set by main() once the window exists.
    @Volatile var quit: (() -> Unit)? = null

    /** Quits and starts again. No-op when [isAvailable] is false or the app is not up yet. */
    fun restart() {
        val q = quit ?: return
        if (!isAvailable) return
        requested = true
        q()
    }

    /** Called by main() as the very last thing before the process exits. */
    fun relaunchIfRequested() {
        if (!requested) return
        val exe = launcher ?: return
        runCatching { ProcessBuilder(exe).start() }
    }
}
