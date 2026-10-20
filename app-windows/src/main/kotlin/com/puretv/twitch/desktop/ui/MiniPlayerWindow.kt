package com.puretv.twitch.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.puretv.twitch.desktop.ui.components.ExpressiveIconButton
import com.puretv.twitch.desktop.ui.components.ExpressiveIcons
import com.puretv.twitch.desktop.ui.components.ExpressiveSlider
import com.puretv.twitch.desktop.ui.theme.PureTvTheme
import java.awt.MouseInfo
import kotlin.math.roundToInt

/**
 * The mini player's control strip, under the video. The video is a native window that
 * paints over anything Compose draws on it, so the controls sit beside it, never on it.
 * The channel name is the handle for moving the window; the buttons are play or pause,
 * mute, volume, back to the normal window, and close.
 */
@Composable
fun MiniControlStrip(
    shell: AppShellController,
    title: String,
    isPlaying: Boolean,
    volume: Int,
    isMuted: Boolean,
    onTogglePlayPause: () -> Unit,
    onToggleMute: () -> Unit,
    onVolumeChange: (Int) -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
) {
    val c = PureTvTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MiniPlayerGeometry.STRIP_HEIGHT.dp)
            .background(c.surfaceContainer)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().miniWindowDrag(shell),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ExpressiveIconButton(
            icon = if (isPlaying) ExpressiveIcons.Pause else ExpressiveIcons.Play,
            contentDescription = if (isPlaying) "Pause" else "Play",
            onClick = onTogglePlayPause,
            boxSize = 36.dp,
            iconSize = 20.dp,
        )
        ExpressiveIconButton(
            icon = if (isMuted || volume == 0) ExpressiveIcons.VolumeOff else ExpressiveIcons.VolumeUp,
            contentDescription = if (isMuted || volume == 0) "Unmute" else "Mute",
            onClick = onToggleMute,
            boxSize = 36.dp,
            iconSize = 20.dp,
        )
        ExpressiveSlider(
            value = volume / 100f,
            onValueChange = { onVolumeChange((it * 100f).roundToInt()) },
            modifier = Modifier.width(72.dp).padding(horizontal = 4.dp),
        )
        ExpressiveIconButton(
            icon = ExpressiveIcons.Expand,
            contentDescription = "Back to the full window",
            onClick = onExpand,
            boxSize = 36.dp,
            iconSize = 20.dp,
        )
        ExpressiveIconButton(
            icon = ExpressiveIcons.Close,
            contentDescription = "Close the stream",
            onClick = onClose,
            boxSize = 36.dp,
            iconSize = 20.dp,
        )
    }
}

/**
 * Drags the whole window while the pointer is held on this handle. The window moves by
 * the screen distance the pointer travelled (read from the OS, because the window
 * moving under the pointer would make Compose's own deltas drift), and is nudged back
 * onto a screen when the drag ends.
 */
private fun Modifier.miniWindowDrag(shell: AppShellController): Modifier = pointerInput(shell) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val start = MouseInfo.getPointerInfo().location
        val x0 = shell.windowX
        val y0 = shell.windowY
        var moved = false
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
            val now = MouseInfo.getPointerInfo().location
            shell.moveWindowTo(x0 + now.x - start.x, y0 + now.y - start.y)
            moved = true
            event.changes.forEach { it.consume() }
        }
        if (moved) shell.settleMini()
    }
}
