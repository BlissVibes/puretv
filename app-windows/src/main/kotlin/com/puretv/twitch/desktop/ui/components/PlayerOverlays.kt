package com.puretv.twitch.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.puretv.twitch.desktop.ui.PlayerShortcuts
import com.puretv.twitch.desktop.ui.theme.PureTvTheme
import com.puretv.twitch.desktop.ui.theme.PureTvType

/**
 * A one-line notice in the player column, such as "Switching to alice" or the sleep
 * timer's last-minute warning. Lives between the video and the controls like the
 * playback menu: the video surface is a native window that paints above anything
 * drawn over it, so a notice cannot float on the picture.
 */
@Composable
fun PlayerNoticeStrip(text: String?, modifier: Modifier = Modifier) {
    // The last words stay while the strip collapses, instead of an empty bar.
    var lastText by remember { mutableStateOf("") }
    if (text != null) lastText = text
    AnimatedVisibility(
        visible = text != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        val c = PureTvTheme.colors
        Text(
            lastText,
            style = PureTvType.data,
            color = c.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surfaceContainer)
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

/** The keyboard shortcuts list (the `?` key), from the same table the keys use. */
@Composable
fun ShortcutsSheet(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        val c = PureTvTheme.colors
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface)
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Kicker("Keyboard shortcuts")
            // Two columns, so the whole list fits above the controls.
            val rows = PlayerShortcuts.help
            val half = (rows.size + 1) / 2
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                listOf(rows.take(half), rows.drop(half)).forEach { column ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                        column.forEach { row ->
                            Row {
                                Text(row.keys, style = PureTvType.data, color = c.primary, modifier = Modifier.width(190.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(row.what, style = PureTvType.data, color = c.onSurface)
                            }
                        }
                    }
                }
            }
            Text("Press ? or Esc to close.", style = PureTvType.dataSmall, color = c.outline)
        }
    }
}
