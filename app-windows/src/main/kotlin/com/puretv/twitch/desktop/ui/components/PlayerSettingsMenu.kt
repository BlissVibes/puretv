package com.puretv.twitch.desktop.ui.components

import androidx.compose.foundation.background
import com.puretv.twitch.desktop.player.NativeLibs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.puretv.twitch.core.model.PlaybackBackend
import com.puretv.twitch.core.model.StreamQuality
import com.puretv.twitch.core.model.UpscalingMode
import com.puretv.twitch.core.playback.SleepTimer
import com.puretv.twitch.core.playback.SleepTimerText
import com.puretv.twitch.desktop.ui.theme.PureTvTheme
import com.puretv.twitch.desktop.ui.theme.PureTvType

/**
 * The in-player "Playback" menu: one panel reused by the live stream and VOD
 * players (highlights are VODs). It consolidates the per-playback controls:
 * Resolution (source quality, live), Scaling (mpv GPU upscaler, live), and Engine
 * (restart-gated). Rendered in the player Column (NOT floating over the video,
 * the heavyweight AWT Canvas paints above Compose), styled to the Cinémathèque
 * system. Stateless: the caller owns current values + callbacks.
 *
 * @param scalingEnabled false on the VLC backend, which has no GPU upscaler here, so
 *   the Scaling section then shows guidance instead of a dead control.
 */
@Composable
fun PlayerSettingsMenu(
    currentQuality: StreamQuality,
    onQualitySelected: (StreamQuality) -> Unit,
    upscalingMode: UpscalingMode,
    onUpscalingSelected: (UpscalingMode) -> Unit,
    scalingEnabled: Boolean,
    backend: PlaybackBackend,
    onBackendSelected: (PlaybackBackend) -> Unit,
    modifier: Modifier = Modifier,
    // Live streams only (the Z key cycles the same presets): the sleep timer's minutes, 0 for Off.
    sleepMinutes: Int? = null,
    onSleepSelected: (Int) -> Unit = {},
) {
    val c = PureTvTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(c.surface)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PlayerMenuSection("Resolution") {
            SegmentedControl(StreamQuality.entries.toList(), currentQuality, { it.label }, onQualitySelected)
        }
        // Without mpv (macOS) there is no engine choice and no GPU upscaling to offer.
        if (NativeLibs.mpvSupported()) PlayerMenuSection("Scaling") {
            if (scalingEnabled) {
                SegmentedControl(UpscalingMode.entries.toList(), upscalingMode, { it.label }, onUpscalingSelected)
                Text(
                    "Sharp = general; Anime = animation. Hold X to compare against Off, F3 for live stats.",
                    style = PureTvType.dataSmall,
                    color = c.outline,
                )
            } else {
                Text(
                    "Switch engine to mpv for GPU upscaling.",
                    style = PureTvType.data,
                    color = c.outline,
                )
            }
        }
        if (NativeLibs.mpvSupported()) PlayerMenuSection("Engine") {
            SegmentedControl(PlaybackBackend.entries.toList(), backend, { it.label }, onBackendSelected)
            Text("Applies after restart.", style = PureTvType.dataSmall, color = c.outline)
        }
        if (sleepMinutes != null) {
            PlayerMenuSection("Sleep timer") {
                SegmentedControl(SleepTimer.PRESETS_MINUTES, sleepMinutes, { SleepTimerText.preset(it) }, onSleepSelected)
                Text("Stops playback when the time is up. Press Z to cycle.", style = PureTvType.dataSmall, color = c.outline)
            }
        }
    }
}

@Composable
private fun PlayerMenuSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Kicker(title)
        content()
    }
}
