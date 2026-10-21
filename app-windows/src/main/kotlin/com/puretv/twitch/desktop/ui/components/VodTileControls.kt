package com.puretv.twitch.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.puretv.twitch.desktop.player.PlayerStatus
import com.puretv.twitch.desktop.player.VlcPlayer
import com.puretv.twitch.desktop.player.formatTimecode
import com.puretv.twitch.desktop.ui.VodTileLogic
import com.puretv.twitch.desktop.ui.theme.PureTvTheme
import com.puretv.twitch.desktop.ui.theme.PureTvType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private val IdleStatus = MutableStateFlow(PlayerStatus())

/**
 * Play or pause, the time, a seek bar and the length of a past video. It sits in the strip
 * below the video, never over it. The player's clock is collected here and nowhere higher,
 * so the ticking time recomposes this row and not the grid or the chat beside it.
 *
 * Dragging only moves the thumb; the seek is made once on release, so the picture does not
 * jump about while the bar is held.
 */
@Composable
fun VodTransportRow(
    player: VlcPlayer?,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PureTvTheme.colors
    val status by (player?.status ?: IdleStatus).collectAsState()
    var dragMs by remember { mutableStateOf<Long?>(null) }
    // After a seek the bar keeps showing the target until VLC's clock catches up with it
    // (or a second passes), so it does not jump back to the old place and forward again.
    var pendingMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(pendingMs) {
        val target = pendingMs ?: return@LaunchedEffect
        withTimeoutOrNull(VodTileLogic.SEEK_TIMEOUT_MS) {
            snapshotFlow { status.positionMs }.first { VodTileLogic.seekSettled(target, it) }
        }
        pendingMs = null
    }
    val shown = dragMs ?: pendingMs ?: status.positionMs
    Row(
        modifier = modifier.fillMaxWidth().height(40.dp).background(c.surfaceContainer).padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ExpressiveIconButton(
            icon = if (status.isPlaying) ExpressiveIcons.Pause else ExpressiveIcons.Play,
            contentDescription = if (status.isPlaying) "Pause" else "Play",
            onClick = onTogglePlay,
            boxSize = 36.dp,
            iconSize = 18.dp,
            enabled = player != null,
        )
        Text(formatTimecode(shown), style = PureTvType.dataSmall, color = c.onSurfaceVariant, modifier = Modifier.widthIn(min = 44.dp))
        ExpressiveSlider(
            value = VodTileLogic.fraction(shown, status.durationMs),
            onValueChange = { dragMs = VodTileLogic.positionAt(it, status.durationMs) },
            onValueChangeFinished = {
                dragMs?.let {
                    pendingMs = it
                    onSeek(it)
                }
                dragMs = null
            },
            // Also after the end, where a seek plays the video again from the target.
            enabled = status.durationMs > 0,
            modifier = Modifier.weight(1f),
        )
        Text(formatTimecode(status.durationMs), style = PureTvType.dataSmall, color = c.onSurfaceVariant)
    }
}
