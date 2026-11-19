package com.puretv.twitch.desktop.platform

import com.puretv.twitch.core.perf.TimingLog
import javax.swing.Timer

/**
 * Diagnostics only: -Dpuretv.diag.stalls=true logs every time the UI thread was busy
 * for more than [THRESHOLD_MS] (`ui.stall <ms>` in timing.log). A Swing timer asks for
 * a tick every [TICK_MS]; a late tick is time the window could not repaint, which is
 * what a viewer sees as a freeze or a white flash. Off unless the property is set, so
 * normal runs never wake the UI thread for it.
 */
object UiStallWatch {
    private const val TICK_MS = 50
    private const val THRESHOLD_MS = 100L

    fun installIfAsked() {
        if (System.getProperty("puretv.diag.stalls") != "true") return
        var last = System.nanoTime()
        Timer(TICK_MS) {
            val now = System.nanoTime()
            val gap = (now - last) / 1_000_000 - TICK_MS
            last = now
            if (gap > THRESHOLD_MS) TimingLog.log("ui.stall", gap)
        }.start()
    }
}
