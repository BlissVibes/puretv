package com.puretv.twitch.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.puretv.twitch.core.model.VideoInfo
import com.puretv.twitch.core.model.VideoType
import com.puretv.twitch.core.repository.ChannelRepository
import com.puretv.twitch.core.repository.VodRepository
import com.puretv.twitch.desktop.player.formatTimecode
import com.puretv.twitch.desktop.ui.ParsedVideo
import com.puretv.twitch.desktop.ui.TileSource
import com.puretv.twitch.desktop.ui.VodKind
import com.puretv.twitch.desktop.ui.VodTileLogic
import com.puretv.twitch.desktop.ui.components.CoverImage
import com.puretv.twitch.desktop.ui.components.ExpressiveButton
import com.puretv.twitch.desktop.ui.components.ExpressiveButtonSize
import com.puretv.twitch.desktop.ui.components.ExpressiveChipRow
import com.puretv.twitch.desktop.ui.components.ExpressiveIcons
import com.puretv.twitch.desktop.ui.theme.PureTvTheme
import com.puretv.twitch.desktop.ui.theme.PureTvType
import kotlinx.coroutines.launch
import org.koin.core.Koin

private val PICKER_FILTERS: List<VideoType?> = listOf(null, VideoType.ARCHIVE, VideoType.HIGHLIGHT)

private fun pickerFilterLabel(type: VideoType?): String = when (type) {
    VideoType.ARCHIVE -> "Broadcasts"
    VideoType.HIGHLIGHT -> "Highlights"
    else -> "All"
}

/**
 * Pick a past broadcast or highlight to add to multi-stream: type a channel to see its latest
 * videos, or paste a twitch.tv/videos link (or the video number) to add that video at once.
 * The pasted link's own start time (`?t=1h2m3s`) is kept.
 */
@Composable
fun VodPickerPanel(
    koin: Koin,
    onFocusChanged: (Boolean) -> Unit,
    onPick: (TileSource.Vod) -> Unit,
) {
    val c = PureTvTheme.colors
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<VideoType?>(null) }
    var channelLogin by remember { mutableStateOf("") }
    var videos by remember { mutableStateOf<List<VideoInfo>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    // True when the channel was found and its videos are shown.
    suspend fun load(login: String, type: VideoType?): Boolean {
        loading = true
        status = null
        val found = runCatching {
            val channel = koin.get<ChannelRepository>().getChannel(login) ?: error("no such channel")
            channel to koin.get<VodRepository>().videosFor(channel.id, type, 12).videos
        }
        loading = false
        val result = found.getOrNull()
        if (result == null) {
            videos = emptyList()
            status = "Couldn't find videos for $login."
            return false
        }
        channelLogin = result.first.login
        videos = result.second
        status = if (videos.isEmpty()) "No saved videos for ${result.first.displayName}." else null
        return true
    }

    fun pickVideo(video: ParsedVideo) {
        // The title and channel are looked up by the tile once it opens.
        onPick(TileSource.Vod(video.videoId, "", "", video.startMs))
        typed = ""
    }

    fun submit() {
        val text = typed.trim()
        if (text.isEmpty()) return
        val video = VodTileLogic.parseVideo(text)
        // A link or a v123 id is a video. A bare number is a channel first: only a long one
        // that is no channel is taken for a video id.
        if (video != null && video.certain) {
            pickVideo(video)
            return
        }
        scope.launch {
            if (!load(text.removePrefix("@").lowercase(), filter) && video != null) pickVideo(video)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(c.surfaceHigh)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (typed.isEmpty()) {
                    Text(
                        "A channel name, or paste a twitch.tv/videos link",
                        color = c.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                BasicTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    textStyle = TextStyle(color = c.onSurface, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    cursorBrush = SolidColor(c.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChanged(it.isFocused) },
                )
            }
            ExpressiveButton(text = "Find videos", onClick = ::submit, size = ExpressiveButtonSize.Medium, icon = ExpressiveIcons.Search)
        }
        if (channelLogin.isNotEmpty()) {
            ExpressiveChipRow(
                options = PICKER_FILTERS,
                selected = filter,
                label = ::pickerFilterLabel,
                onSelect = {
                    filter = it
                    scope.launch { load(channelLogin, it) }
                },
            )
        }
        when {
            loading -> Text("Loading...", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            status != null -> Text(status!!, color = c.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            videos.isNotEmpty() -> Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                videos.forEach { v ->
                    Column(
                        modifier = Modifier
                            .width(168.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(c.surfaceHigh)
                            .clickable {
                                onPick(
                                    TileSource.Vod(
                                        videoId = v.id,
                                        title = v.title,
                                        channelLogin = v.userLogin.ifBlank { channelLogin },
                                        kind = VodKind.from(v.type),
                                        thumbnailUrl = v.thumbnailUrl,
                                    ),
                                )
                            },
                    ) {
                        Box(Modifier.fillMaxWidth().height(94.dp)) {
                            CoverImage(imageUrl = v.thumbnailUrl, seed = v.id, contentDescription = v.title, modifier = Modifier.size(168.dp, 94.dp))
                            Text(
                                formatTimecode(v.durationSeconds * 1000),
                                style = PureTvType.dataSmall,
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Text(
                            v.title.ifBlank { "Untitled" },
                            style = MaterialTheme.typography.labelMedium,
                            color = c.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}
