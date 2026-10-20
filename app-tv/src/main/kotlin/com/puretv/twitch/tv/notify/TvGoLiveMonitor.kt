package com.puretv.twitch.tv.notify

import android.util.Log
import com.puretv.twitch.core.model.StreamInfo
import com.puretv.twitch.core.notify.GoLiveBannerQueue
import com.puretv.twitch.core.notify.LiveFollowsMonitor
import com.puretv.twitch.tv.data.AppSettingsStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Go-live alerts on TV: an in-app banner ([banners], drawn by TvGoLiveBanner), not
 * a system notification. Android TV and Google TV file a sideloaded app's
 * notifications away in a settings panel, so a banner is the only alert a viewer
 * on the sofa will actually see, and it needs no permission.
 *
 * Polls only while the app is on screen: TvMainActivity calls [start] from onStart
 * and [stop] from onStop, so a TV app left in the background never touches the
 * network. The interval counts from the last fetch by anyone, so Home's own
 * refresh of "Live now" (which reads through the same monitor) stands in for a
 * tick instead of adding one.
 */
class TvGoLiveMonitor(
    // Lazy: start() runs from onStart on the main thread, and building the settings
    // store there would open the encrypted token prefs on the first frame. Both are
    // first touched inside the IO job below.
    private val monitor: Lazy<LiveFollowsMonitor>,
    private val settingsStore: Lazy<AppSettingsStore>,
) {
    val banners = GoLiveBannerQueue()

    /** The channel playing right now (kept current by TvMainActivity); never bannered. */
    @Volatile
    var watchingLogin: String? = null

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.w(TAG, "Go-live polling stopped", e) },
    )
    private var job: Job? = null

    /** What the shared monitor just saw go live; shown in turn by the banner. */
    fun announce(streams: List<StreamInfo>) {
        banners.offer(streams, watchingLogin)
    }

    /** Polls while banners are on and someone is signed in; collectLatest stops it when either changes. */
    fun start() {
        job?.cancel()
        job = scope.launch {
            settingsStore.value.flow
                .map { it.liveNotifications && it.accessToken.isNotBlank() && it.userId.isNotBlank() }
                .distinctUntilChanged()
                .collectLatest { active -> if (active) monitor.value.pollEvery(POLL_INTERVAL_MS) }
        }
    }

    /** Leaving the screen: stop polling, and drop banners that would be stale by the time the viewer is back. */
    fun stop() {
        job?.cancel()
        job = null
        banners.clear()
    }

    private companion object {
        const val TAG = "TvGoLiveMonitor"

        /** One small Helix call, so a banner appears within about two minutes. */
        const val POLL_INTERVAL_MS = 120_000L
    }
}
