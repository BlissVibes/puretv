package com.puretv.twitch.android.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.puretv.twitch.android.data.AppSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.compose.getKoin

/** True on Android 13+ while PureTV may not post notifications yet. */
fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/**
 * A function that asks for notification permission (Android 13+; a no-op on older
 * versions, where it is granted at install). [onResult] gets the answer.
 */
@Composable
fun rememberNotificationPermissionRequest(onResult: (Boolean) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> onResult(granted) }
    return {
        if (needsNotificationPermission(context)) {
            runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
}

/**
 * Go-live alerts are on by default, so signing in (or opening the app signed in)
 * with them on asks for the permission they need. Nothing waits on the answer, and
 * Android itself stops showing the prompt once the viewer has declined it twice;
 * Settings keeps the switch and a way to the system settings after that.
 */
@Composable
fun AskForGoLiveNotifications() {
    val context = LocalContext.current
    val koin = getKoin()
    val request = rememberNotificationPermissionRequest()
    LaunchedEffect(Unit) {
        if (!needsNotificationPermission(context)) return@LaunchedEffect
        // Off the main thread: resolving the settings store opens the encrypted prefs.
        val wanted = runCatching {
            withContext(Dispatchers.IO) { koin.get<AppSettingsStore>().flow.first().liveNotifications }
        }.getOrDefault(false)
        if (wanted) request()
    }
}
