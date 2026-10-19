package com.puretv.twitch.desktop.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** How chat rows look, from Settings > Chat. Provided around the chat list. */
data class ChatAppearance(
    val showTimestamps: Boolean = false,
    val showBadges: Boolean = true,
    val compact: Boolean = false,
)

val LocalChatAppearance = staticCompositionLocalOf { ChatAppearance() }

/**
 * Scales everything inside [content] (text, emotes, badges, spacing) by [scale],
 * so a larger chat text size keeps emotes in proportion with the words around
 * them instead of only growing the letters.
 */
@Composable
fun ChatScale(scale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density * scale, density.fontScale),
        content = content,
    )
}
