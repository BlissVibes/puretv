package com.puretv.twitch.tv.ui.screens

import com.puretv.twitch.tv.ui.theme.TvShelfScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import com.puretv.twitch.core.model.VideoInfo
import com.puretv.twitch.core.vod.VodFormat
import com.puretv.twitch.tv.ui.VodLaunch
import com.puretv.twitch.tv.ui.components.tvFocusClickable
import java.time.Instant
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.puretv.twitch.core.model.ChannelInfo
import com.puretv.twitch.tv.ui.ChannelViewModel
import com.puretv.twitch.tv.ui.components.ExpressiveIcons
import com.puretv.twitch.tv.ui.components.TvButtonSize
import com.puretv.twitch.tv.ui.components.TvButtonStyle
import com.puretv.twitch.tv.ui.components.TvExpressiveButton
import com.puretv.twitch.tv.ui.components.TvExpressiveIconButton
import com.puretv.twitch.tv.ui.components.TvLivePill
import com.puretv.twitch.tv.ui.components.TvPanel
import com.puretv.twitch.tv.ui.components.formatTvViewerCount
import com.puretv.twitch.tv.ui.theme.PureTvTvTheme
import com.puretv.twitch.tv.ui.theme.PureTvTvType
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * SECTION 07.2: channel profile, a banner that fades into the surface colour,
 * a large avatar riding its lower edge, identity + a live pill, a mono
 * metadata line, and a prominent "Watch now" CTA that's focused by default so
 * the D-pad confirm key immediately starts playback (Section 7.3's "primary
 * action gets initial focus" convention for detail screens).
 *
 * [ChannelViewModel] exposes no follow state on TV, unlike its desktop
 * counterpart, so no follow control is wired here: adding one would mean a
 * control with nothing behind it, which the shared brief rules out. See the
 * redesign report for that gap.
 */
@Composable
fun TvChannelScreen(
    channelLogin: String,
    onWatch: () -> Unit,
    onOpenVod: (VodLaunch) -> Unit,
    onBack: () -> Unit,
    viewModel: ChannelViewModel = koinViewModel(parameters = { parametersOf(channelLogin) }),
) {
    val state by viewModel.state.collectAsState()
    val c = PureTvTvTheme.colors
    val watchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(state.channel) {
        if (state.channel != null) runCatching { watchFocusRequester.requestFocus() }
    }

    val channel = state.channel
    val displayName = channel?.displayName ?: channelLogin
    val bannerUrl = channel?.offlineImageUrl?.takeIf { it.isNotBlank() }
        ?: channel?.profileImageUrl?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.surface)
            .verticalScroll(rememberScrollState()),
    ) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp)) {
            if (!bannerUrl.isNullOrBlank()) {
                AsyncImage(
                    model = bannerUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(c.surfaceHigh))
            }
            // The banner protects no fixed content of its own, so it can fade
            // straight into the page colour rather than needing a hard cut.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to c.surface.copy(alpha = 0f), 0.65f to c.surface.copy(alpha = 0.55f), 1f to c.surface),
                ),
            )
            TvExpressiveIconButton(
                icon = ExpressiveIcons.Back,
                contentDescription = "Back",
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(32.dp),
            )
        }

        Column(modifier = Modifier.padding(horizontal = 48.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            IdentityRow(displayName = displayName, channel = channel, isLive = state.isLive, viewerCount = state.viewerCount)

            TvExpressiveButton(
                text = "Watch now",
                onClick = onWatch,
                style = TvButtonStyle.Filled,
                size = TvButtonSize.XLarge,
                icon = ExpressiveIcons.Play,
                modifier = Modifier.focusRequester(watchFocusRequester),
            )

            if (state.videos.isNotEmpty()) {
                PastBroadcastsRow(
                    videos = state.videos,
                    hasMore = state.videoCursor != null,
                    loading = state.videosLoading,
                    onOpen = onOpenVod,
                    onMore = viewModel::loadMoreVideos,
                )
            }

            if (!channel?.description.isNullOrBlank()) {
                TvPanel {
                    Column {
                        Text(text = "About", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
                        Spacer(Modifier.size(12.dp))
                        Text(text = channel!!.description, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.size(40.dp))
        }
    }
}

/**
 * The channel's past broadcasts, twenty at a time: a row of focusable cards
 * ending in a "More" card that loads the next page (or a quiet "Loading" card
 * while it does).
 */
@Composable
private fun PastBroadcastsRow(
    videos: List<VideoInfo>,
    hasMore: Boolean,
    loading: Boolean,
    onOpen: (VodLaunch) -> Unit,
    onMore: () -> Unit,
) {
    val c = PureTvTvTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Past broadcasts", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
        val now = remember { System.currentTimeMillis() }
        // Where focus goes when "More" has done its job. Clicking More remembers how many
        // cards there were; once the page lands (or More goes away), focus moves to the
        // first new card, or the last one if nothing came, rather than dropping to the
        // screen root. Any card taking focus on its own cancels the hand-off, and so does
        // focus leaving the row while the page is still loading.
        val landing = remember { FocusRequester() }
        var pendingFrom by remember { mutableStateOf<Int?>(null) }
        var landingIndex by remember { mutableStateOf<Int?>(null) }
        val stillLoading by rememberUpdatedState(loading)
        LaunchedEffect(videos.size, loading, hasMore) {
            val from = pendingFrom ?: return@LaunchedEffect
            if (loading) return@LaunchedEffect
            if (videos.size > from || !hasMore) {
                landingIndex = if (videos.size > from) from else videos.lastIndex
                // The card needs a frame to take the requester before it can be asked for focus.
                withFrameNanos { }
                if (pendingFrom != null && runCatching { landing.requestFocus() }.isFailure) {
                    withFrameNanos { }
                    if (pendingFrom != null) runCatching { landing.requestFocus() }
                }
            }
            pendingFrom = null
            landingIndex = null
        }
        TvShelfScroll {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                // A More card that has gone takes the focus with it, but by then the page has
                // loaded; leaving mid-load means the viewer went elsewhere, so let the hand-off go.
                modifier = Modifier.onFocusChanged { if (!it.hasFocus && stillLoading) pendingFrom = null },
            ) {
                itemsIndexed(videos, key = { _, v -> v.id }) { index, video ->
                    TvVideoCard(
                        modifier = (if (index == landingIndex) Modifier.focusRequester(landing) else Modifier)
                            .onFocusChanged { if (it.hasFocus) pendingFrom = null },
                        video = video,
                        now = now,
                        onClick = {
                            onOpen(
                                VodLaunch(
                                    vodId = video.id,
                                    channelLogin = video.userLogin,
                                    title = video.title,
                                    thumbnailUrl = VodFormat.thumbnail(video.thumbnailUrl),
                                ),
                            )
                        },
                    )
                }
                if (hasMore || loading) {
                    item(key = "more") {
                        TvMoreCard(
                            loading = loading,
                            onClick = {
                                pendingFrom = videos.size
                                onMore()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvVideoCard(modifier: Modifier, video: VideoInfo, now: Long, onClick: () -> Unit) {
    val c = PureTvTvTheme.colors
    val shapes = PureTvTvTheme.shapes
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .padding(6.dp)
            .width(260.dp)
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
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shapes.thumbShape).background(c.surfaceHigh)) {
            AsyncImage(
                model = VodFormat.thumbnail(video.thumbnailUrl, 440, 248),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                text = VodFormat.timecode(video.durationSeconds * 1000),
                style = PureTvTvType.dataSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Text(
            text = video.title.ifBlank { "Untitled" },
            style = MaterialTheme.typography.titleMedium,
            color = c.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        val aired = runCatching { Instant.parse(video.createdAt).toEpochMilli() }.getOrNull()
        Text(
            text = listOfNotNull(
                aired?.let { VodFormat.age(it, now) },
                "${formatTvViewerCount(video.viewCount.toLong())} views",
            ).joinToString("  ·  "),
            style = PureTvTvType.data,
            color = c.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun TvMoreCard(loading: Boolean, onClick: () -> Unit) {
    val c = PureTvTvTheme.colors
    val shapes = PureTvTvTheme.shapes
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(6.dp)
            .width(160.dp)
            .aspectRatio(1f)
            .tvFocusClickable(
                interaction = interaction,
                onClick = { if (!loading) onClick() },
                restRadius = shapes.card,
                focusRadius = shapes.cardFocus,
                color = c.surfaceContainer,
                focusColor = c.surfaceHigh,
            ),
    ) {
        Text(
            text = if (loading) "Loading" else "More",
            style = MaterialTheme.typography.titleMedium,
            color = c.onSurface,
        )
    }
}

/** The avatar, name, live pill and mono metadata line, straddling the banner's lower edge. */
@Composable
private fun IdentityRow(displayName: String, channel: ChannelInfo?, isLive: Boolean, viewerCount: Long) {
    val c = PureTvTvTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth().offset(y = (-64).dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        ChannelAvatar(displayName = displayName, imageUrl = channel?.profileImageUrl)
        Spacer(Modifier.size(28.dp))
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.displayMedium,
                    color = c.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isLive) TvLivePill(trailing = "${formatTvViewerCount(viewerCount)} viewers")
            }
            val meta = buildList {
                channel?.broadcasterType?.takeIf { it.isNotBlank() }?.let { add(it.replaceFirstChar(Char::uppercase)) }
                channel?.viewCount?.takeIf { it > 0 }?.let { add("${formatTvViewerCount(it.toLong())} views") }
            }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                Text(text = meta.joinToString("  ·  "), style = PureTvTvType.data, color = c.onSurfaceVariant)
            }
        }
    }
}

/** 160dp hero avatar: the channel's profile image, or an initial on a flat fill. */
@Composable
private fun ChannelAvatar(displayName: String, imageUrl: String?) {
    val c = PureTvTvTheme.colors
    Box(
        modifier = Modifier.size(160.dp).clip(CircleShape).background(c.surfaceHigh),
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
                text = displayName.take(1).uppercase(),
                style = MaterialTheme.typography.displayMedium,
                color = c.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
