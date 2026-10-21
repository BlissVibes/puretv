package com.puretv.twitch.tv.ui.components

import com.puretv.twitch.tv.ui.theme.PureTvTvMotion
import androidx.compose.animation.core.tween
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.puretv.twitch.core.chat.BadgeIndex
import com.puretv.twitch.core.chat.RankChip
import com.puretv.twitch.core.chat.missingRankChips
import com.puretv.twitch.core.chat.ChatRoomModes
import com.puretv.twitch.core.chat.labels
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.tv.ui.theme.PureTvTvTheme
import com.puretv.twitch.tv.ui.theme.PureTvTvType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ClockFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * SECTION 07.4 — semi-transparent right-side chat rail toggled by the
 * remote's MENU button (DPAD_RIGHT also reveals it per spec; DPAD_LEFT
 * returns focus to the player and dismisses it). Read-only on TV — typing
 * chat messages with a D-pad isn't a primary use case, so no compose box
 * is rendered (mirrors real Twitch-on-TV apps' chat treatment).
 *
 * Two placements (Settings > Chat position): floating over the video, sliding in
 * from the right edge, or [beside] it in its own column so nothing is covered.
 * Beside, the panel fades instead of sliding: the caller's row gives it its space
 * at once, so the video resizes in one step rather than on every frame.
 */
@Composable
fun TvChatOverlay(
    visible: Boolean,
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    /** Settings > Chat text size, as a scale for the message list. */
    textScale: Float = 1f,
    /** The room's chat modes, shown under the header while any is on. */
    roomModes: ChatRoomModes = ChatRoomModes(),
    /** Chat badge art; until it loads (or for an unknown badge) rows show text chips. */
    badges: BadgeIndex = BadgeIndex.EMPTY,
    /** Settings > Chat > badges. */
    showBadges: Boolean = true,
    /** Third-party emote name-to-url map rows match words against. */
    emotes: Map<String, String> = emptyMap(),
    /** True when chat sits in its own column beside the video instead of over it. */
    beside: Boolean = false,
    /** Settings > Chat background: how solid the panel and its rows are (0.4 to 1). */
    opacity: Float = 0.85f,
) {
    val c = PureTvTvTheme.colors
    val shapes = PureTvTvTheme.shapes
    val listState = rememberLazyListState()

    // Key on the newest message id, not messages.size: the list is capped at 200,
    // so its size stops changing and a size-keyed effect stopped scrolling as soon
    // as a busy chat filled up. A quiet chat glides to each new line; a busy one
    // snaps, because an animation restarted on every batch never settles.
    val lastScrollAt = remember { longArrayOf(0L) }
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isEmpty()) return@LaunchedEffect
        val now = SystemClock.uptimeMillis()
        val busy = now - lastScrollAt[0] < GLIDE_MIN_GAP_MS
        lastScrollAt[0] = now
        if (busy) listState.scrollToItem(messages.lastIndex) else listState.animateScrollToItem(messages.lastIndex)
    }

    AnimatedVisibility(
        visible = visible,
        enter = if (beside) PureTvTvMotion.ControlsEnter else slideInHorizontally(tween(PureTvTvMotion.Medium, easing = PureTvTvMotion.Standard), initialOffsetX = { it }),
        exit = if (beside) PureTvTvMotion.ControlsExit else slideOutHorizontally(tween(PureTvTvMotion.Exit, easing = PureTvTvMotion.Accelerate), targetOffsetX = { it }),
        modifier = modifier,
    ) {
        // AnimatedVisibility's content scope is not a BoxScope, so alignment can't
        // be applied to the rail directly. Fill the transition area and align the
        // fixed-width rail to the right edge from here, with a margin so all four
        // corners of the panel below actually show their rounding.
        Box(
            modifier = if (beside) Modifier.fillMaxHeight().padding(24.dp) else Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(420.dp)
                    .clip(shapes.paneShape)
                    .background(c.surfaceContainer.copy(alpha = opacity))
                    .padding(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Chat",
                        style = MaterialTheme.typography.titleMedium,
                        color = c.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    TvRoomModeChips(roomModes)
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density * textScale, density.fontScale)) {
                        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(messages, key = { it.id }) { message ->
                                TvChatMessageRow(
                                    message,
                                    emotes = emotes,
                                    badges = badges,
                                    showBadges = showBadges,
                                    opacity = opacity,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Slow, emote-only, followers-only and sub-only as muted pills under the header,
 * so a quiet or emote-only chat explains itself. Nothing when the room is open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvRoomModeChips(modes: ChatRoomModes) {
    val labels = remember(modes) { modes.labels() }
    if (labels.isEmpty()) return
    val c = PureTvTvTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { label ->
            Text(
                text = label,
                style = PureTvTvType.dataSmall,
                color = c.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(PureTvTvTheme.shapes.pillShape)
                    .background(c.surfaceHigh)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * One chat line, its own rounded card at [shapes.mdShape]. A fast-moving feed
 * reads as distinct rows from three metres away this way, rather than a
 * run-on wall of same-weight text with no boundary between messages.
 */
@Composable
private fun TvChatMessageRow(
    message: ChatMessage,
    emotes: Map<String, String>,
    badges: BadgeIndex,
    showBadges: Boolean,
    opacity: Float,
) {
    val c = PureTvTvTheme.colors
    // Subs, raids, "chat cleared" and Twitch's notices carry no user identity: one
    // centred, muted line instead of a card with a name.
    if (message.isSystem) {
        Text(
            text = message.message,
            style = MaterialTheme.typography.bodyMedium,
            color = c.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        )
        return
    }
    val shapes = PureTvTvTheme.shapes
    val nameColor = remember(message.color, c.primary) {
        runCatching { Color(android.graphics.Color.parseColor(message.color)) }.getOrDefault(c.primary)
    }
    val timestamp = remember(message.timestamp) {
        runCatching {
            Instant.ofEpochMilli(message.timestamp).atZone(ZoneId.systemDefault()).format(ClockFormatter)
        }.getOrDefault("")
    }
    // Keyed on the whole message, not its id: a moderator's delete arrives as a
    // copy with the same id. A new emote map (a live 7TV change) re-renders too.
    val (bodyText, bodyInline) = remember(message, emotes) { buildTvChatBody(message, emotes) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shapes.mdShape)
            // A line that @-mentions the viewer stands out from across the room. The
            // card follows the panel's opacity so a see-through panel stays see-through.
            .background((if (message.mentionsSelf) c.primaryContainer else c.surfaceHigh).copy(alpha = opacity))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBadges) TvChatBadges(message, badges)
            Text(
                text = message.displayName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = nameColor,
            )
            Text(text = timestamp, style = PureTvTvType.dataSmall, color = c.onSurfaceVariant)
        }
        if (message.deleted) {
            // A moderator removed this (timeout, ban or delete). The row stays as a
            // tombstone so the conversation around it still reads.
            Text(
                text = "<message deleted>",
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
                color = c.onSurfaceVariant,
            )
        } else {
            Text(
                text = bodyText,
                inlineContent = bodyInline,
                style = MaterialTheme.typography.bodyLarge,
                color = c.onSurface,
            )
        }
    }
}

/**
 * The sender's badges as images (22 dp, readable from the sofa), or text chips
 * for any rank whose art is still loading or unknown, so a moderator
 * or the streamer still stands out.
 */
@Composable
private fun TvChatBadges(message: ChatMessage, badges: BadgeIndex) {
    val c = PureTvTvTheme.colors
    val resolved = remember(message.badges, badges) {
        if (badges === BadgeIndex.EMPTY) emptyList()
        else message.badges.mapNotNull { badges.resolve(it.setId, it.version) }
    }
    resolved.forEach { badge ->
        AsyncImage(model = badge.url, contentDescription = badge.title, modifier = Modifier.size(22.dp))
    }
    missingRankChips(message, badges).forEach { chip ->
        when (chip) {
            RankChip.HOST -> TvBadgeChip("HOST", c.primary, c.onPrimary)
            RankChip.MOD -> TvBadgeChip("MOD", c.tertiaryContainer, c.onTertiaryContainer)
            RankChip.SUB -> TvBadgeChip("SUB", c.secondaryContainer, c.onSecondaryContainer)
        }
    }
}

@Composable
private fun TvBadgeChip(text: String, bg: Color, fg: Color) {
    Text(
        text = text,
        style = PureTvTvType.dataSmall,
        color = fg,
        modifier = Modifier
            .clip(PureTvTvTheme.shapes.pillShape)
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** New lines closer together than this snap into view instead of gliding. */
private const val GLIDE_MIN_GAP_MS = 500L
