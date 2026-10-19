package com.puretv.twitch.android

import android.app.Application
import com.puretv.twitch.android.data.AppSettingsStore
import com.puretv.twitch.android.data.TokenRefresher
import com.puretv.twitch.android.di.androidModule
import com.puretv.twitch.android.player.BackgroundPlayback
import com.puretv.twitch.core.adblock.AdBlockEngine
import com.puretv.twitch.core.di.coreModule
import android.util.Log
import com.puretv.twitch.core.perf.TimingLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

/**
 * SECTION 11 — wires `coreModule` (shared) + `androidModule` (phone/tablet
 * specific: Room DB, ExoPlayer, DataStore, ViewModels) into a single Koin graph.
 */
class PureTvApp : Application() {

    // Process-lifetime scope for one-shot startup work. SupervisorJob so one
    // failure never tears down the others.
    // A handler, because a SupervisorJob alone does not make this fail-soft: an
    // exception with nowhere to go reaches the thread's default handler and the
    // process dies before the first frame. Every job below is best-effort
    // priming, so none of them is worth a launch failure. The viewer sees a
    // signed-out app or a stale setting instead, which they can act on.
    private val startupFailures = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Startup priming failed, continuing without it", e)
    }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + startupFailures)

    override fun onCreate() {
        super.onCreate()
        // Local-only timing marks (time to first frame and so on) go to logcat;
        // read them with `adb logcat -s PureTvTiming`. Nothing is sent anywhere.
        TimingLog.sink = { line -> Log.i("PureTvTiming", line) }
        val koinApp = startKoin {
            androidLogger()
            androidContext(this@PureTvApp)
            modules(coreModule, androidModule)
        }

        appScope.launch {
            // Construct AppSettingsStore OFF the main thread. Its init forces the
            // EncryptedSharedPreferences open (Android Keystore master-key build +
            // prefs decrypt), and on the very first launch the key is GENERATED,
            // which can take hundreds of ms. Doing it here keeps that disk + crypto
            // work off the first composition, where it would risk a startup ANR /
            // visible jank. This also primes the shared core TokenHolder so the
            // first authenticated request has a bearer token.
            koinApp.koin.get<AppSettingsStore>()
            // The update check lives in MainActivity: this runs for every process
            // start, including the go-live worker's, which must never contact GitHub.
        }

        // Keep the sign-in alive for as long as the process runs (background play
        // outlives the ~4h token). TokenRefresher only goes to the network when the
        // token is within minutes of expiring, so the tick itself costs a prefs read.
        // MainActivity.onResume adds a check on every return to the app.
        appScope.launch {
            koinApp.koin.get<AppSettingsStore>()
            val refresher = koinApp.koin.get<TokenRefresher>()
            while (isActive) {
                runCatching { refresher.refreshIfNeeded() }
                    .onFailure { Log.w(TAG, "Sign-in check failed, will retry", it) }
                delay(SESSION_CHECK_INTERVAL_MS)
            }
        }

        // Keep the in-process ad-block interceptor in sync with the user's
        // "Enable ad block" switch. The singleton AdBlockEngine + interceptor are
        // process-wide, so a single long-lived collector owns this; flipping the
        // switch in Settings takes effect on the next playlist refresh.
        appScope.launch {
            val engine = koinApp.koin.get<AdBlockEngine>()
            koinApp.koin.get<AppSettingsStore>().flow
                .map { it.adBlockEnabled }
                .distinctUntilChanged()
                .collect { engine.setEnabled(it) }
        }

        // Same pattern for "Background play": the stream screen and MainActivity
        // read it synchronously as the app leaves the foreground, so a process-
        // wide holder is kept current rather than suspending on DataStore there.
        appScope.launch {
            val playback = koinApp.koin.get<BackgroundPlayback>()
            koinApp.koin.get<AppSettingsStore>().flow
                .map { it.backgroundPlay }
                .distinctUntilChanged()
                .collect { playback.setEnabled(it) }
        }
        appScope.launch {
            val playback = koinApp.koin.get<BackgroundPlayback>()
            koinApp.koin.get<AppSettingsStore>().flow
                .map { it.pipOnLeave }
                .distinctUntilChanged()
                .collect { playback.setPipOnLeave(it) }
        }
        appScope.launch {
            val playback = koinApp.koin.get<BackgroundPlayback>()
            koinApp.koin.get<AppSettingsStore>().flow
                .map { it.audioOnlyInBackground }
                .distinctUntilChanged()
                .collect { playback.setAudioOnlyInBackground(it) }
        }
    }

    private companion object {
        const val TAG = "PureTvApp"
        const val SESSION_CHECK_INTERVAL_MS = 5 * 60 * 1_000L
    }
}
