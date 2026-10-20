package com.puretv.twitch.android.notify

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.puretv.twitch.android.data.AppSettingsStore
import com.puretv.twitch.android.data.TokenRefresher
import com.puretv.twitch.core.notify.LiveFollowsMonitor
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * The background half of phone go-live alerts: one `/streams/followed` poll per run,
 * every 15 minutes (WorkManager's floor), only with a network. What just went live
 * is posted by the shared [LiveFollowsMonitor]'s callback, so this only has to ask.
 *
 * Always [Result.success], including on failure: a retry would only wake the radio
 * again sooner, and the next period tries anyway. A process started just for this
 * run never contacts GitHub (the update check lives in MainActivity) and only
 * renews the sign-in when it is about to lapse, or once on a 401 (see AndroidModule).
 */
class GoLiveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val settingsStore: AppSettingsStore by inject()
    private val tokenRefresher: TokenRefresher by inject()
    private val monitor: LiveFollowsMonitor by inject()
    private val inApp: GoLiveMonitor by inject()
    private val notifications: GoLiveNotifications by inject()

    override suspend fun doWork(): Result {
        try {
            val settings = settingsStore.flow.first()
            if (!settings.liveNotifications || settings.accessToken.isBlank()) return Result.success()
            // On screen, the in-app timer is already polling more often than this.
            if (inApp.isAppVisible) return Result.success()
            // Nothing could be shown (permission denied, notifications blocked for the
            // app), so asking Twitch would only wake the radio for nothing.
            if (!notifications.canPost()) return Result.success()
            tokenRefresher.refreshIfNeeded()
            monitor.pollNow()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            Log.w(TAG, "Go-live check failed, the next period tries again", e)
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "GoLiveWorker"
    }
}

/** Keeps exactly one periodic go-live job scheduled while alerts are on and someone is signed in. */
object GoLiveScheduler {
    private const val WORK_NAME = "go-live"

    /**
     * Called on every process start and whenever the toggle or the session changes.
     * KEEP leaves an existing schedule alone (the request never changes), so a
     * process the worker itself started does not reset its own period.
     */
    fun sync(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<GoLiveWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
