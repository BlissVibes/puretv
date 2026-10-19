package com.puretv.twitch.tv.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
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
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = modifier,
    ) {
        // AnimatedVisibility's content scope is not a BoxScope, so alignment can't
        // be applied to the rail directly. Fill the transition area and align the
        // fixed-width rail to the right edge from here, with a margin so all four
        // corners of the panel below actually show their rounding.
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.CenterEnd) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(420.dp)
                    .clip(shapes.paneShape)
                    .background(c.surfaceContainer)
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
                                TvChatMessageRow(message, badges = badges, showBadges = showBadges)
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
private fun TvChatMessageRow(message: ChatMessage, badges: BadgeIndex, showBadges: Boolean) {
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shapes.mdShape)
            // A line that @-mentions the viewer stands out from across the room.
            .background(if (message.mentionsSelf) c.primaryContainer else c.surfaceHigh)
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
                text = message.message,
                style = MaterialTheme.typography.bodyLarge,
                color = c.onSurface,
            )
        }
    }
}

/**
 * The sender's badges as images (22 dp, readable from the sofa), or text chips
 * while the art loads or when the channel's badges are unknown, so a moderator
 * or the streamer still stands out.
 */
@Composable
private fun TvChatBadges(message: ChatMessage, badges: BadgeIndex) {
    val c = PureTvTvTheme.colors
    val resolved = remember(message.badges, badges) {
        if (badges === BadgeIndex.EMPTY) emptyList()
        else message.badges.mapNotNull { badges.resolve(it.setId, it.version) }
    }
    if (resolved.isNotEmpty()) {
        resolved.forEach { badge ->
            AsyncImage(model = badge.url, contentDescription = badge.title, modifier = Modifier.size(22.dp))
        }
        return
    }
    if (message.isBroadcaster) {
        TvBadgeChip("HOST", c.primary, c.onPrimary)
    } else if (message.isModerator) {
        TvBadgeChip("MOD", c.tertiaryContainer, c.onTertiaryContainer)
    }
    if (message.isSubscriber) TvBadgeChip("SUB", c.secondaryContainer, c.onSecondaryContainer)
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
