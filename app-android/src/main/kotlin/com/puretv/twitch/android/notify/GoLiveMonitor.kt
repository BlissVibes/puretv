package com.puretv.twitch.android.notify

import com.puretv.twitch.android.data.AppSettingsStore
import com.puretv.twitch.core.notify.LiveFollowsMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The fast, in-app half of phone go-live alerts: while PureTV is on screen (PiP
 * included) it polls every [IN_APP_INTERVAL_MS]. Off screen, [GoLiveWorker] takes
 * over at WorkManager's 15 minute floor, so the app never keeps the radio awake in
 * the background on its own.
 *
 * Both halves drive the one [LiveFollowsMonitor], whose lock and saved state mean
 * they can never announce the same stream twice, and whose interval counts from the
 * last fetch by anyone (Home and Following refreshing count too).
 *
 * MainActivity reports visibility from onStart/onStop. That is the process's
 * foreground state for this single-Activity app, without a lifecycle-process
 * dependency.
 */
class GoLiveMonitor(
    // Lazy: MainActivity reports visibility from onStart on the main thread, and
    // building the settings store there would open the encrypted token prefs on
    // the first frame. Both are first touched in [start], on IO.
    private val monitor: Lazy<LiveFollowsMonitor>,
    private val settingsStore: Lazy<AppSettingsStore>,
) {
    private val visible = MutableStateFlow(false)

    /** True while the app is on screen and polling itself; the worker stands down then. */
    val isAppVisible: Boolean get() = visible.value

    fun setAppVisible(isVisible: Boolean) {
        visible.value = isVisible
    }

    /**
     * Runs for the life of the process (started by PureTvApp). The poll loop exists
     * only while alerts are on, someone is signed in and the app is visible;
     * collectLatest cancels it the moment any of those stops being true.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        val wanted = settingsStore.value.flow
            .map { it.liveNotifications && it.accessToken.isNotBlank() && it.userId.isNotBlank() }
            .distinctUntilChanged()
        combine(wanted, visible) { on, onScreen -> on && onScreen }
            .distinctUntilChanged()
            .collectLatest { active -> if (active) monitor.value.pollEvery(IN_APP_INTERVAL_MS) }
    }

    companion object {
        /** In-app cadence: one small Helix call, so an alert lands within about two minutes. */
        const val IN_APP_INTERVAL_MS = 120_000L
    }
}
