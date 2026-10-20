package com.puretv.twitch.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.puretv.twitch.tv.ui.theme.PureTvTvTheme

/**
 * The channel-flip banner: "Switching to alice" while a flip settles, or a short note
 * when there was nowhere to flip to. Like the go-live banner it is plain layout and text,
 * never focusable, so the remote keeps driving the player underneath.
 */
@Composable
fun TvFlipBanner(target: String?, note: String?, modifier: Modifier = Modifier) {
    val text = when {
        target != null -> "Switching to $target"
        else -> note
    }
    // The last words stay while the banner fades out, instead of an empty box.
    var lastText by remember { mutableStateOf("") }
    if (text != null) lastText = text
    AnimatedVisibility(visible = text != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val c = PureTvTvTheme.colors
        val shape = RoundedCornerShape(20.dp)
        Row(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 560.dp)
                .clip(shape)
                .background(c.surfaceHigh)
                .border(1.dp, c.outlineVariant, shape)
                .padding(horizontal = 28.dp, vertical = 18.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                lastText,
                style = MaterialTheme.typography.titleLarge,
                color = c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
