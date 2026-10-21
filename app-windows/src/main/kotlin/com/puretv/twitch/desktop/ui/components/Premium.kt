package com.puretv.twitch.desktop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.puretv.twitch.core.model.StreamInfo
import com.puretv.twitch.desktop.ui.theme.LocalQuietMotion
import com.puretv.twitch.desktop.ui.theme.PureTvMotion
import com.puretv.twitch.desktop.ui.theme.PureTvTheme
import com.puretv.twitch.desktop.ui.theme.PureTvType

// ── Stream card ────────────────────────────────────────────────────────────────

/**
 * The shelf card: a tonal container holding 16:9 art, then the channel identity
 * below it. The whole card rounds further out and lifts on hover rather than
 * tinting its title, so the feedback is the same gesture used everywhere else.
 */
@Composable
fun StreamCard(stream: StreamInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = PureTvTheme.colors
    val shapes = PureTvTheme.shapes

    ExpressiveCard(onClick = onClick, modifier = modifier) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(shapes.thumbShape),
            ) {
                val thumbUrl = stream.thumbnailUrl
                    .replace("{width}", "440")
                    .replace("{height}", "248")
                CoverImage(
                    imageUrl = thumbUrl,
                    seed = stream.userName,
                    contentDescription = stream.title,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(c.cardScrim))
                LivePill(Modifier.align(Alignment.TopStart).padding(10.dp), height = 24.dp)
                Text(
                    formatViewerCount(stream.viewerCount),
                    style = PureTvType.dataSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                )
            }

            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // The Helix streams payload carries no avatar URL, so this is the
                    // deterministic initial chip rather than a fetch-per-card.
                    Avatar(stream.userName, imageUrl = null, size = 32)
                    Column(Modifier.weight(1f)) {
                        Text(stream.userName, style = MaterialTheme.typography.titleMedium, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (stream.gameName.isNotBlank()) {
                            Text(stream.gameName, style = MaterialTheme.typography.bodyMedium, color = c.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (stream.title.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stream.title, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

internal fun formatViewerCount(count: Int): String = when {
    // Audit U7: without the millions branch, a 1.2M-viewer stream rendered as
    // the nonsensical "1200.0K".
    count >= 1_000_000 -> "${count / 1_000_000}.${(count % 1_000_000) / 100_000}M"
    count >= 1_000 -> "${count / 1_000}.${(count % 1_000) / 100}K"
    else -> count.toString()
}

// ── Section header ─────────────────────────────────────────────────────────────

/** Kept for call sites that predate [SectionHeading]; same thing, one voice. */
@Composable
fun SectionHeader(title: String, onSeeAll: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    SectionHeading(
        title = title,
        modifier = modifier,
        actionLabel = if (onSeeAll != null) "See all" else null,
        onAction = onSeeAll,
    )
}

// ── Skeleton shimmer ───────────────────────────────────────────────────────────

/**
 * Loading placeholder: the resting surface colour with a soft highlight band sweeping
 * across it. The band is drawn in the draw phase, so a frame costs one rect draw and
 * no recomposition. The sweep is driven from the frame clock's time, not from a
 * per-instance animation, so every skeleton on screen sweeps in step.
 *
 * Only for lists and cards that are loading. Never put one on the player screen: the
 * frame clock it holds awake would run for as long as the video does.
 */
@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val c = PureTvTheme.colors
    val highlight by rememberUpdatedState(c.surfaceHighest)
    // Still while animations are off, and while a video is playing (the rail skeleton).
    val reduced = PureTvMotion.reduced || LocalQuietMotion.current
    var phase by remember { mutableFloatStateOf(0f) }
    if (!reduced) {
        LaunchedEffect(Unit) {
            while (true) {
                withFrameNanos { phase = (it / 1_000_000L % SHIMMER_PERIOD_MS).toFloat() / SHIMMER_PERIOD_MS }
            }
        }
    }
    Box(
        modifier
            .clipToBounds()
            .background(c.surfaceLow)
            .drawWithCache {
                val band = size.width * 0.6f
                val brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, highlight, Color.Transparent),
                    startX = 0f,
                    endX = band,
                )
                onDrawBehind {
                    if (!reduced) {
                        val x = PureTvMotion.Decelerate.transform(phase) * (size.width + band) - band
                        translate(left = x) { drawRect(brush, size = Size(band, size.height)) }
                    }
                }
            },
    )
}

private const val SHIMMER_PERIOD_MS = 1_400L

@Composable
fun StreamCardSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Skeleton(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(PureTvTheme.shapes.thumbShape))
        Spacer(Modifier.height(8.dp))
        Skeleton(modifier = Modifier.fillMaxWidth(0.6f).height(14.dp).clip(RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(6.dp))
        Skeleton(modifier = Modifier.fillMaxWidth(0.85f).height(12.dp).clip(RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(4.dp))
        Skeleton(modifier = Modifier.fillMaxWidth(0.45f).height(12.dp).clip(RoundedCornerShape(4.dp)))
    }
}

// ── Avatar ─────────────────────────────────────────────────────────────────────

@Composable
fun Avatar(displayName: String, imageUrl: String?, size: Int = 36, modifier: Modifier = Modifier) {
    val c = PureTvTheme.colors
    val sizeDp = size.dp
    if (!imageUrl.isNullOrBlank()) {
        AsyncImage(
            model = imageUrl,
            contentDescription = displayName,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(sizeDp).clip(CircleShape).border(1.dp, c.outlineVariant, CircleShape),
        )
    } else {
        Box(
            modifier = modifier.size(sizeDp).clip(CircleShape).background(c.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(displayName.take(1).uppercase(), style = MaterialTheme.typography.labelLarge, color = c.onPrimaryContainer, fontWeight = FontWeight.Bold)
        }
    }
}
