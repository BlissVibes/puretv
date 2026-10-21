package com.puretv.twitch.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.puretv.twitch.android.data.AppSettingsStore
import com.puretv.twitch.core.model.AppSettings
import com.puretv.twitch.core.notify.GoLiveAlerts
import com.puretv.twitch.core.notify.GoLiveText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * One channel's go-live bell: whether alerts are on for it, a tap handler, and a short
 * confirmation to show beside the bell for a few seconds after a tap. Alerts are per
 * channel, so every bell on screen (channel page, stream screen) is this one state.
 * Signed out, a tap explains that alerts need a Twitch sign-in instead of saving
 * a channel that could never ring.
 */
class AlertBell(val on: Boolean, val message: String?, val toggle: () -> Unit)

@Composable
fun rememberAlertBell(login: String, displayName: String): AlertBell {
    val store = koinInject<AppSettingsStore>()
    val settings by store.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val askPermission = rememberNotificationPermissionRequest()
    val on = GoLiveAlerts.isOn(settings.notifyChannels, login)
    var message by remember(login) { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            delay(CONFIRMATION_MS)
            message = null
        }
    }
    return AlertBell(on, message) {
        if (!on && settings.accessToken.isBlank()) {
            message = "Sign in to get go-live alerts"
        } else {
            scope.launch { store.update { GoLiveAlerts.bellTapped(it, login) } }
            if (!on) askPermission()
            message = GoLiveText.bellConfirmation(displayName, !on)
        }
    }
}

private const val CONFIRMATION_MS = 3_500L
