package com.puretv.twitch.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.puretv.twitch.core.model.AppSettings
import com.puretv.twitch.core.notify.GoLiveAlerts
import com.puretv.twitch.core.notify.GoLiveText
import com.puretv.twitch.tv.data.AppSettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * One channel's go-live bell on TV: whether the banner is on for it, a select
 * handler, and a short confirmation to show beside the button. Works signed out too.
 */
class TvAlertBell(val on: Boolean, val message: String?, val toggle: () -> Unit)

@Composable
fun rememberTvAlertBell(login: String, displayName: String): TvAlertBell {
    val store = koinInject<AppSettingsStore>()
    val settings by store.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val on = GoLiveAlerts.isOn(settings.notifyChannels, login)
    var message by remember(login) { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            delay(CONFIRMATION_MS)
            message = null
        }
    }
    return TvAlertBell(on, message) {
        scope.launch { store.update { GoLiveAlerts.bellTapped(it, login) } }
        message = GoLiveText.bellConfirmation(displayName, !on)
    }
}

private const val CONFIRMATION_MS = 3_500L
