package com.puretv.twitch.desktop.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.isTraySupported
import com.puretv.twitch.core.notify.GoLiveAlerts
import com.puretv.twitch.core.notify.GoLiveText
import com.puretv.twitch.desktop.data.DesktopSettingsStore
import kotlinx.coroutines.delay

/**
 * One channel's go-live bell: whether alerts are on for it, a tap handler, and a short
 * confirmation to show beside the bell for a few seconds after a tap. Alerts are per
 * channel, so every bell on screen (channel page, stream header) is this one state.
 */
class AlertBell(val on: Boolean, val message: String?, val toggle: () -> Unit)

@Composable
fun rememberAlertBell(settingsStore: DesktopSettingsStore, login: String, displayName: String): AlertBell {
    val settings by settingsStore.settings.collectAsState()
    val on = GoLiveAlerts.isOn(settings.notifyChannels, login)
    var message by remember(login) { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            delay(CONFIRMATION_MS)
            message = null
        }
    }
    return AlertBell(on, message) {
        if (!on && !isTraySupported) {
            // Alerts are delivered through the tray icon; promising one that cannot show would be a lie.
            message = "Go-live alerts need the Windows system tray, which is not available here"
        } else {
            settingsStore.updateSettings { GoLiveAlerts.bellTapped(it, login) }
            message = GoLiveText.bellConfirmation(displayName, !on)
        }
    }
}

private const val CONFIRMATION_MS = 3_500L
