package com.puretv.twitch.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.puretv.twitch.tv.ui.theme.PureTvTvTheme

/**
 * The sleep timer's last-minute warning ("Stopping in 42 seconds. Press any button to
 * keep watching"). [text] is null outside that minute. Never focusable: any button press
 * is what keeps the viewer watching, so it must reach the player screen untouched.
 */
@Composable
fun TvSleepBanner(text: String?, modifier: Modifier = Modifier) {
    // The last words stay while the banner fades out, instead of an empty box.
    var lastText by remember { mutableStateOf("") }
    if (text != null) lastText = text
    AnimatedVisibility(visible = text != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val c = PureTvTvTheme.colors
        val shape = RoundedCornerShape(20.dp)
        Text(
            lastText,
            style = MaterialTheme.typography.titleLarge,
            color = c.onSurface,
            modifier = Modifier
                .widthIn(max = 640.dp)
                .clip(shape)
                .background(c.surfaceHigh)
                .border(1.dp, c.outlineVariant, shape)
                .padding(horizontal = 28.dp, vertical = 18.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
