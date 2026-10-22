package com.puretv.twitch.desktop.ui.screens

import com.puretv.twitch.desktop.platform.ShortcutKey
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.puretv.twitch.core.model.ChannelInfo
import com.puretv.twitch.core.model.VideoInfo
import com.puretv.twitch.core.model.VideoType
import com.puretv.twitch.desktop.player.formatTimecode
import com.puretv.twitch.desktop.ui.ChannelViewModel
import com.puretv.twitch.desktop.ui.AddOutcome
import com.puretv.twitch.desktop.ui.MultiSession
import com.puretv.twitch.desktop.ui.VodLaunch
import com.puretv.twitch.desktop.ui.note
import com.puretv.twitch.desktop.ui.toTileSource
import kotlinx.coroutines.delay
import com.puretv.twitch.desktop.ui.VodListViewModel
import com.puretv.twitch.desktop.data.DesktopSettingsStore
import com.puretv.twitch.desktop.ui.components.AlertBell
import com.puretv.twitch.desktop.ui.components.CoverImage
import com.puretv.twitch.desktop.ui.components.ExpressiveButton
import com.puretv.twitch.desktop.ui.components.ExpressiveButtonSize
import com.puretv.twitch.desktop.ui.components.ExpressiveButtonStyle
import com.puretv.twitch.desktop.ui.components.ExpressiveCard
import com.puretv.twitch.desktop.ui.components.ExpressiveChipRow
import com.puretv.twitch.desktop.ui.components.ExpressiveIconButton
import com.puretv.twitch.desktop.ui.components.ExpressiveIcons
import com.puretv.twitch.desktop.ui.components.ExpressivePanel
import com.puretv.twitch.desktop.ui.components.LivePill
import com.puretv.twitch.desktop.ui.components.LocalStreamPrewarm
import com.puretv.twitch.desktop.ui.components.expressiveSurface
import com.puretv.twitch.desktop.ui.components.formatViewerCount
import com.puretv.twitch.desktop.ui.components.rememberAlertBell
import com.puretv.twitch.desktop.ui.rememberDesktopViewModel
import com.puretv.twitch.desktop.ui.WindowDestination
import androidx.compose.ui.platform.LocalWindowInfo
import com.puretv.twitch.desktop.ui.components.MenuAction
import com.puretv.twitch.desktop.ui.components.contextMenu
import com.puretv.twitch.desktop.ui.openInNewWindow
import com.puretv.twitch.desktop.ui.theme.PureTvTheme
import com.puretv.twitch.desktop.ui.theme.PureTvType
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/**
 * Channel profile: a full-bleed banner that rides the pane's own top corners,
 * an avatar straddling its lower edge, the identity + action row, and a
 * two-column body pairing About / past broadcasts against the audience stats.
 *
 * Only real [ChannelViewModel] state drives this screen: `state.channel`
 * ([com.puretv.twitch.core.model.ChannelInfo]), `state.isLive`, and
 * `isFollowed`, so the header shows strictly the fields Twitch returns for a
 * channel (broadcaster type, total views). No live viewer/game data is
 * surfaced in the header because this screen's own state never exposes it;
 * that lives in [ChannelStatsPanel], which polls independently.
 */
@Composable
fun ChannelContent(koin: Koin, channelLogin: String, onWatch: () -> Unit, onPlayVod: (VodLaunch) -> Unit, onBack: () -> Unit) {
    val viewModel = rememberDesktopViewModel(channelLogin) {
        koin.get<ChannelViewModel> { parametersOf(channelLogin) }
    }
    val state by viewModel.state.collectAsState()
    val isFollowed by viewModel.isFollowed.collectAsState()
    val c = PureTvTheme.colors

    // Opening a live channel's page is the strongest hint the viewer is about to
    // press Watch: resolve the stream now so playback starts at once.
    val prewarm = LocalStreamPrewarm.current
    LaunchedEffect(state.isLive, channelLogin) {
        if (state.isLive) prewarm?.invoke(channelLogin)
    }

    val channel = state.channel
    val displayName = channel?.displayName ?: channelLogin
    val bell = rememberAlertBell(remember { koin.get<DesktopSettingsStore>() }, channelLogin, displayName)
    val seed = channel?.login ?: channelLogin
    // Prefer the channel's offline banner; fall back to the profile portrait so
    // the duotone seed is never the only thing carrying the header.
    val bannerUrl = channel?.offlineImageUrl?.takeIf { it.isNotBlank() }
        ?: channel?.profileImageUrl?.takeIf { it.isNotBlank() }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // The content pane itself is clipped to PureTvTheme.shapes.paneShape, and
        // this banner sits flush at its top edge, so the top corners round for
        // free; the banner's own bottom edge is a plain cut, mid-scroll.
        Box(modifier = Modifier.fillMaxWidth().height(240.dp)) {
            CoverImage(imageUrl = bannerUrl, seed = seed, contentDescription = displayName, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to c.surface.copy(alpha = 0f), 0.6f to c.surface.copy(alpha = 0.5f), 1f to c.surface),
                ),
            )
            ExpressiveIconButton(
                icon = ExpressiveIcons.Back,
                contentDescription = "Back",
                onClick = onBack,
                style = ExpressiveButtonStyle.Tonal,
                modifier = Modifier.align(Alignment.TopStart).padding(20.dp),
            )
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
            IdentityRow(
                displayName = displayName,
                channel = channel,
                isLive = state.isLive,
                isFollowed = isFollowed,
                bell = bell,
                onWatch = onWatch,
                onToggleFollow = viewModel::toggleFollow,
                onWatchInNewWindow = { openInNewWindow(WindowDestination.Stream(channelLogin)) },
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    AboutPanel(channel?.description)
                    Spacer(Modifier.height(28.dp))
                    channel?.id?.let { userId ->
                        PastBroadcastsSection(koin = koin, userId = userId, channelLogin = channelLogin, onPlayVod = onPlayVod)
                    }
                }
                ChannelStatsPanel(koin = koin, channelLogin = channelLogin)
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * The 144dp hero avatar, the display name + live pill, the metadata line, and
 * the primary actions, straddling the banner's lower edge by -72dp.
 */
@Composable
private fun IdentityRow(
    displayName: String,
    channel: ChannelInfo?,
    isLive: Boolean,
    isFollowed: Boolean,
    bell: AlertBell,
    onWatch: () -> Unit,
    onToggleFollow: () -> Unit,
    onWatchInNewWindow: () -> Unit,
) {
    val c = PureTvTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth().offset(y = (-72).dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        HeroAvatar(displayName = displayName, imageUrl = channel?.profileImageUrl)
        Spacer(Modifier.width(20.dp))
        Column(modifier = Modifier.weight(1f).padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.displayMedium,
                    color = c.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isLive) {
                    Spacer(Modifier.width(14.dp))
                    LivePill()
                }
            }
            // Metadata line: mono, real ChannelInfo fields only, separated by " · ".
            val meta = buildList {
                channel?.broadcasterType?.takeIf { it.isNotBlank() }
                    ?.let { add(it.replaceFirstChar(Char::uppercase)) }
                channel?.viewCount?.takeIf { it > 0 }
                    ?.let { add("${formatViewerCount(it)} views") }
            }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
            }
            bell.message?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = c.primary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ExpressiveButton(
                text = if (isLive) "Watch now" else "Channel is offline",
                onClick = onWatch,
                style = ExpressiveButtonStyle.Filled,
                size = ExpressiveButtonSize.Large,
                icon = ExpressiveIcons.Play,
                enabled = isLive,
            )
            ExpressiveButton(
                text = if (isFollowed) "Following" else "Follow",
                onClick = onToggleFollow,
                style = ExpressiveButtonStyle.Outlined,
                size = ExpressiveButtonSize.Large,
                icon = if (isFollowed) ExpressiveIcons.Check else ExpressiveIcons.Add,
                enabled = channel != null,
            )
            ExpressiveButton(
                text = if (bell.on) "Alerts on" else "Notify me",
                onClick = bell.toggle,
                style = if (bell.on) ExpressiveButtonStyle.Tonal else ExpressiveButtonStyle.Outlined,
                size = ExpressiveButtonSize.Large,
                icon = if (bell.on) ExpressiveIcons.NotifyOn else ExpressiveIcons.Notify,
            )
            ExpressiveButton(
                text = "Watch in new window",
                onClick = onWatchInNewWindow,
                style = ExpressiveButtonStyle.Outlined,
                size = ExpressiveButtonSize.Large,
                icon = ExpressiveIcons.OpenInNew,
                enabled = isLive,
            )
        }
    }
}

/**
 * The avatar rests at 48dp radius and morphs to a full circle (72dp, half its
 * 144dp box) on hover. Built directly on [expressiveSurface] rather than
 * [com.puretv.twitch.desktop.ui.components.Avatar], which has no morph.
 */
@Composable
private fun HeroAvatar(displayName: String, imageUrl: String?) {
    val c = PureTvTheme.colors
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(144.dp)
            .hoverable(interaction)
            .expressiveSurface(
                interaction = interaction,
                restRadius = 48.dp,
                hoverRadius = 72.dp,
                color = c.surfaceHigh,
                borderWidth = 5.dp,
                borderColor = c.surface,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                displayName.take(1).uppercase(),
                style = MaterialTheme.typography.displayMedium,
                color = c.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun AboutPanel(description: String?) {
    val c = PureTvTheme.colors
    ExpressivePanel(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("About", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
            Spacer(Modifier.height(12.dp))
            val text = description?.takeIf { it.isNotBlank() }
            Text(
                text ?: "This channel hasn't written a bio yet.",
                style = MaterialTheme.typography.bodyLarge,
                color = if (text != null) c.onSurfaceVariant else c.outline,
            )
        }
    }
}

@Composable
private fun PastBroadcastsSection(koin: Koin, userId: String, channelLogin: String, onPlayVod: (VodLaunch) -> Unit) {
    val vm = rememberDesktopViewModel(userId) { koin.get<VodListViewModel> { parametersOf(userId) } }
    val state by vm.state.collectAsState()
    val c = PureTvTheme.colors
    val session = remember { koin.get<MultiSession>() }
    // What the add and pop out buttons did, for a few seconds.
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(note) {
        if (note != null) {
            delay(3_000)
            note = null
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Past broadcasts", style = MaterialTheme.typography.headlineMedium, color = c.onSurface)
        note?.let { Text(it, style = PureTvType.data, color = c.primary) }
        Spacer(Modifier.height(14.dp))
        ExpressiveChipRow(
            options = VOD_FILTERS,
            selected = state.filter,
            label = ::vodFilterLabel,
            onSelect = vm::setFilter,
        )
        Spacer(Modifier.height(16.dp))
        when {
            state.error != null -> Text("Couldn't load videos.", style = PureTvType.data, color = c.outline)
            state.videos.isEmpty() && !state.loading -> Text("No saved videos.", style = PureTvType.data, color = c.outline)
        }
        val windowInfo = LocalWindowInfo.current
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.videos.forEach { v ->
                VodRow(
                    v,
                    // Ctrl held opens the video in a new window instead of here.
                    onClick = {
                        if (ShortcutKey.isHeld(windowInfo.keyboardModifiers)) {
                            openInNewWindow(WindowDestination.Vod(v.id, channelLogin, v.title, v.thumbnailUrl))
                        } else {
                            onPlayVod(VodLaunch(v.id, channelLogin, v.title, v.thumbnailUrl))
                        }
                    },
                    channelLogin = channelLogin,
                    onAddToMulti = {
                        val outcome = session.addVod(v.toTileSource(channelLogin))
                        note = outcome.note(popped = false)
                        // A new tile is in the grid, which only shows on the multi-stream screen: go there.
                        if (outcome == AddOutcome.Added) session.requestGrid()
                    },
                    onPopOut = { note = session.popOutVod(v.toTileSource(channelLogin)).note(popped = true) },
                )
            }
        }
        if (state.cursor != null) {
            Spacer(Modifier.height(12.dp))
            ExpressiveButton(
                text = if (state.loading) "Loading…" else "Load more",
                onClick = vm::loadMore,
                style = ExpressiveButtonStyle.Outlined,
                enabled = !state.loading,
            )
        }
    }
}

/** A VOD row: 150x84 thumbnail with its duration chip, title + mono meta, a tonal play button. */
@Composable
private fun VodRow(v: VideoInfo, onClick: () -> Unit, channelLogin: String, onAddToMulti: () -> Unit, onPopOut: () -> Unit) {
    val c = PureTvTheme.colors
    val shapes = PureTvTheme.shapes
    val inNewWindow = WindowDestination.Vod(v.id, channelLogin, v.title, v.thumbnailUrl)

    ExpressiveCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().contextMenu(
            actions = {
                listOf(
                    MenuAction("Open in new window") { openInNewWindow(inNewWindow) },
                    MenuAction("Add to multi-stream", onAddToMulti),
                    MenuAction("Pop out into its own window", onPopOut),
                )
            },
            onMiddleClick = { openInNewWindow(inNewWindow) },
        ),
        contentPadding = 12.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(modifier = Modifier.size(width = 150.dp, height = 84.dp).clip(shapes.thumbShape)) {
                CoverImage(imageUrl = v.thumbnailUrl, seed = v.id, contentDescription = v.title, modifier = Modifier.fillMaxSize())
                Text(
                    formatTimecode(v.durationSeconds * 1000),
                    style = PureTvType.dataSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(shapes.pillShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    v.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                    color = c.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatViewerCount(v.viewCount)} views  ·  ${v.createdAt.take(10)}",
                    style = PureTvType.data,
                    color = c.onSurfaceVariant,
                )
            }
            ExpressiveIconButton(
                icon = ExpressiveIcons.Add,
                contentDescription = "Add ${v.title} to multi-stream",
                onClick = onAddToMulti,
                boxSize = 40.dp,
            )
            ExpressiveIconButton(
                icon = ExpressiveIcons.OpenInNew,
                contentDescription = "Pop out ${v.title} into its own window",
                onClick = onPopOut,
                boxSize = 40.dp,
            )
            ExpressiveIconButton(
                icon = ExpressiveIcons.Play,
                contentDescription = "Play ${v.title}",
                onClick = onClick,
                style = ExpressiveButtonStyle.Tonal,
                boxSize = 48.dp,
            )
        }
    }
}

// Backed directly by VodListViewModel's real `filter: VideoType?` state (null = "All").
private val VOD_FILTERS: List<VideoType?> = listOf(null, VideoType.ARCHIVE, VideoType.HIGHLIGHT, VideoType.UPLOAD)

private fun vodFilterLabel(type: VideoType?): String = when (type) {
    null -> "All"
    VideoType.ARCHIVE -> "Broadcasts"
    VideoType.HIGHLIGHT -> "Highlights"
    VideoType.UPLOAD -> "Uploads"
    // VOD_FILTERS never offers this, but the ViewModel's filter type includes it.
    VideoType.UNKNOWN -> "All"
}
