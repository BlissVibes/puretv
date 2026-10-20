package com.puretv.twitch.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.puretv.twitch.core.playback.ContinueItem
import com.puretv.twitch.core.vod.VodFormat
import com.puretv.twitch.tv.ui.theme.PureTvTvTheme
import com.puretv.twitch.tv.ui.theme.PureTvTvType

/**
 * One card of the Home "Continue watching" row for a channel watched before. A channel
 * that is live now is the normal live card (with its live thumbnail); one that is offline
 * is a plain card that says so.
 */
@Composable
fun TvRecentChannelCard(item: ContinueItem.Channel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val stream = item.stream
    if (stream != null) {
        TvStreamCard(stream = stream, onClick = onClick, modifier = modifier)
        return
    }
    ContinueCardFrame(
        title = item.displayName.ifBlank { item.login },
        subtitle = "Offline",
        onClick = onClick,
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize().background(PureTvTvTheme.colors.surfaceHigh))
    }
}

/** A past broadcast to pick up: its picture, the title and a bar for how far in the viewer got. */
@Composable
fun TvVodResumeCard(item: ContinueItem.Vod, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = PureTvTvTheme.colors
    val p = item.progress
    ContinueCardFrame(
        title = p.title.ifBlank { "Past broadcast" },
        subtitle = listOf(
            p.channelLogin.takeIf { it.isNotBlank() },
            "${VodFormat.timecode(p.positionMs)} of ${VodFormat.timecode(p.durationMs)}",
        ).filterNotNull().joinToString(" · "),
        onClick = onClick,
        modifier = modifier,
    ) {
        val thumb = VodFormat.thumbnail(p.thumbnailUrl, 440, 248)
        if (thumb.isNotBlank()) {
            AsyncImage(
                model = thumb,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize().background(c.surfaceHigh))
        }
        Box(Modifier.fillMaxSize().background(c.cardScrim))
        if (p.durationMs > 0) {
            val fraction = (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f)
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.White.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxWidth(fraction).height(5.dp).background(c.primary))
            }
        }
    }
}

/** The shared card grammar of [TvStreamCard]: 16:9 art, then a title and a line under it. */
@Composable
private fun ContinueCardFrame(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier,
    art: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val c = PureTvTvTheme.colors
    val shapes = PureTvTvTheme.shapes
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .padding(6.dp)
            .width(220.dp)
            .tvFocusClickable(
                interaction = interaction,
                onClick = onClick,
                restRadius = shapes.card,
                focusRadius = shapes.cardFocus,
                color = c.surfaceContainer,
                focusColor = c.surfaceHigh,
            )
            .padding(12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shapes.thumbShape),
            content = art,
        )
        Column(Modifier.padding(top = 10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = PureTvTvType.dataSmall,
                color = c.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
