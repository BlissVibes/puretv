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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.puretv.twitch.desktop.ui.components.ExpressiveIconButton
import com.puretv.twitch.desktop.ui.components.ExpressiveIcons
import com.puretv.twitch.desktop.ui.components.ExpressiveSlider
import com.puretv.twitch.desktop.ui.theme.PureTvTheme
import kotlin.math.roundToInt

/**
 * The mini player's control strip, under the video. The video is a native window that
 * paints over anything Compose draws on it, so the controls sit beside it, never on it.
 * The whole strip is a handle for moving the window; the buttons are play or pause,
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
            .miniWindowDrag(shell, onExpand)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight(),
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
 * Drags the whole window while the pointer is held on this element, and a double click on
 * it calls [onDoubleClick]. The window follows the pointer once it travels past the touch
 * slop, so a plain click never moves it, and a press that was a drag never counts as a
 * click. Presses a button already took are left alone.
 */
fun Modifier.miniWindowDrag(shell: AppShellController, onDoubleClick: () -> Unit): Modifier = composed {
    val doubleClick = remember { DoubleClickDetector() }
    val latestDoubleClick by rememberUpdatedState(onDoubleClick)
    pointerInput(shell) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            shell.startMiniDrag()
            var dragging = false
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.none { it.pressed }) break
                // A control inside the strip (the volume slider) took this gesture: not a window drag.
                if (!dragging && event.changes.any { it.isConsumed }) {
                    shell.endMiniDrag()
                    return@awaitEachGesture
                }
                if (!dragging && (event.changes.first().position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    dragging = true
                }
                if (dragging) {
                    shell.dragMiniTo()
                    event.changes.forEach { it.consume() }
                }
            }
            if (dragging) shell.endMiniDrag() else if (doubleClick.click(System.currentTimeMillis())) latestDoubleClick()
        }
    }
}
