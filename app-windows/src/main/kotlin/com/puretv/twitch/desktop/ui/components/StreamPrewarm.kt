package com.puretv.twitch.desktop.ui.components

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import kotlinx.coroutines.delay

/**
 * Starts resolving a channel's stream in the background (`PlayableStreamCache.prewarm`),
 * so opening it a moment later skips the token mint and playlist fetch. Provided at the
 * app root; null (no prewarming) in previews and tests.
 */
val LocalStreamPrewarm = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** How long the pointer must rest on a card before prewarming: passing over is not intent. */
private const val HOVER_DWELL_MS = 300L

/**
 * Prewarms [channelLogin]'s stream once the pointer rests on this element. Only for
 * [live] channels: an offline one has no stream to resolve.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.prewarmStreamOnHover(channelLogin: String, live: Boolean = true): Modifier = composed {
    val prewarm = LocalStreamPrewarm.current
    if (prewarm == null || !live || channelLogin.isBlank()) return@composed Modifier
    var hovered by remember { mutableStateOf(false) }
    LaunchedEffect(hovered, channelLogin) {
        if (!hovered) return@LaunchedEffect
        delay(HOVER_DWELL_MS)
        prewarm(channelLogin)
    }
    Modifier
        .onPointerEvent(PointerEventType.Enter) { hovered = true }
        .onPointerEvent(PointerEventType.Exit) { hovered = false }
}
