package com.puretv.twitch.tv.ui.components

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay

/**
 * Starts resolving a channel's stream in the background (`PlayableStreamCache.prewarm`),
 * so pressing OK a moment later skips the token mint and playlist fetch. Provided by
 * [com.puretv.twitch.tv.TvMainActivity]; null (no prewarming) in previews.
 */
val LocalStreamPrewarm = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * How long focus must rest on a card before prewarming. Holding the D-pad sweeps
 * focus across a whole shelf in a second; only a card the viewer stops on counts.
 */
private const val FOCUS_DWELL_MS = 600L

/** Prewarms [channelLogin]'s stream once D-pad focus rests on this element ([live] channels only). */
fun Modifier.prewarmStreamOnFocus(channelLogin: String, live: Boolean = true): Modifier = composed {
    val prewarm = LocalStreamPrewarm.current
    if (prewarm == null || !live || channelLogin.isBlank()) return@composed Modifier
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused, channelLogin) {
        if (!focused) return@LaunchedEffect
        delay(FOCUS_DWELL_MS)
        prewarm(channelLogin)
    }
    Modifier.onFocusChanged { focused = it.hasFocus }
}
