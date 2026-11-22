package com.puretv.twitch.desktop.ui.components

import com.puretv.twitch.core.audio.AudioLevelling
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import com.puretv.twitch.desktop.player.NativeLibs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.puretv.twitch.core.model.ChatTextSize
import com.puretv.twitch.core.model.PlaybackBackend
import com.puretv.twitch.core.model.StreamQuality
import com.puretv.twitch.core.model.UpscalingMode
import com.puretv.twitch.core.playback.SleepTimer
import com.puretv.twitch.core.playback.SleepTimerText
import com.puretv.twitch.desktop.player.RtxVideoStatus
import com.puretv.twitch.desktop.ui.theme.PureTvTheme

/**
 * The in-player "Playback" menu: one panel reused by the live stream and VOD
 * players (highlights are VODs). It consolidates the per-playback controls:
 * Resolution (source quality, live), Audio (the normalizer, live), Scaling (mpv GPU
 * upscaler, live), and Engine (restart-gated). Rendered in the player Column (NOT floating over the video,
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
    // NVIDIA RTX Video: null hides the section (no RTX card, or a player that can't use it).
    rtx: RtxMenuState? = null,
    // The engine running now differs from [backend]; with [onRestart] the menu offers to
    // restart right away instead of only saying it applies after a restart.
    engineNeedsRestart: Boolean = false,
    onRestart: (() -> Unit)? = null,
    // "Even out loud and quiet audio" (the same settings as in Settings): null hides the
    // section. [audioLevelling] is its strength, offered while it is on.
    audioNormalizer: Boolean? = null,
    onAudioNormalizerChange: (Boolean) -> Unit = {},
    audioLevelling: AudioLevelling = AudioLevelling.DEFAULT,
    onAudioLevellingSelected: (AudioLevelling) -> Unit = {},
    // Live streams: play closer to live (the same setting as Settings > Playback). Null hides it.
    lowLatency: Boolean? = null,
    onLowLatencyChange: (Boolean) -> Unit = {},
    // Live streams: how big chat reads (the same setting as Settings > Chat > Text size). Null hides it.
    chatTextSize: ChatTextSize? = null,
    onChatTextSizeSelected: (ChatTextSize) -> Unit = {},
) {
    val c = PureTvTheme.colors
    // The menu sits between the video and the controls and takes the height it needs. In a
    // small window that is more than there is, so it stops at the space it is given and
    // scrolls; the bar on the right only shows then.
    val scroll = rememberScrollState()
    Box(modifier = modifier.fillMaxWidth().background(c.surface)) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Resolution, with the sleep timer beside it as a pulldown (live streams only).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.weight(1f)) {
                PlayerMenuSection("Resolution") {
                    SegmentedControl(StreamQuality.entries.toList(), currentQuality, { it.label }, onQualitySelected)
                }
            }
            if (sleepMinutes != null) {
                PlayerMenuSection("Sleep timer") {
                    SleepTimerPulldown(sleepMinutes, onSleepSelected)
                }
            }
        }
        // Second from the top: in a small window the lower sections fall outside the menu.
        if (chatTextSize != null) PlayerMenuSection("Chat size") {
            SegmentedControl(ChatTextSize.entries.toList(), chatTextSize, { it.label }, onChatTextSizeSelected)
            Text(
                "How big chat reads. Emotes and badges grow with the text.",
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
            )
        }
        if (lowLatency != null) PlayerMenuSection("Latency") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Low latency", style = MaterialTheme.typography.titleSmall, color = c.onSurface)
                    Text(
                        "Plays a few seconds closer to live, so the stream keeps up with chat. Turn it off if the stream keeps pausing to buffer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 640.dp),
                    )
                }
                ExpressiveSwitch(checked = lowLatency, onCheckedChange = onLowLatencyChange)
            }
        }
        if (rtx != null) PlayerMenuSection("NVIDIA RTX Video") {
            RtxVideoMenuSection(
                status = rtx.status,
                superResolution = rtx.superResolution,
                onSuperResolutionChange = rtx.onSuperResolutionChange,
                hdr = rtx.hdr,
                onHdrChange = rtx.onHdrChange,
            )
        }
        if (audioNormalizer != null) PlayerMenuSection("Audio") {
            AudioNormalizerRow(audioNormalizer, onAudioNormalizerChange)
            if (audioNormalizer) {
                SegmentedControl(AudioLevelling.entries.toList(), audioLevelling, { it.label }, onAudioLevellingSelected)
                Text(audioLevelling.description, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
        // Without mpv (macOS) there is no engine choice and no GPU upscaling to offer.
        if (NativeLibs.mpvSupported()) PlayerMenuSection("Scaling") {
            if (scalingEnabled) {
                SegmentedControl(UpscalingMode.entries.toList(), upscalingMode, { it.label }, onUpscalingSelected)
                Text(
                    "Sharp = general; Anime = animation. Hold X to compare against Off, F3 for live stats.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                )
            } else {
                Text(
                    "Switch engine to mpv for GPU upscaling.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                )
            }
        }
        if (NativeLibs.mpvSupported()) PlayerMenuSection("Engine") {
            SegmentedControl(PlaybackBackend.entries.toList(), backend, { it.label }, onBackendSelected)
            if (engineNeedsRestart && onRestart != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "Switching to ${backend.label} takes a quick restart.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    ExpressiveButton("Restart now", onClick = onRestart, size = ExpressiveButtonSize.Small)
                }
            } else {
                Text("Applies after restart.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
    }
        if (scroll.maxValue > 0) {
            Box(Modifier.matchParentSize().padding(vertical = 6.dp, horizontal = 4.dp)) {
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scroll),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    // The default thumb is black at 12%, which cannot be seen on this surface.
                    style = LocalScrollbarStyle.current.copy(
                        unhoverColor = c.onSurfaceVariant.copy(alpha = 0.4f),
                        hoverColor = c.onSurfaceVariant.copy(alpha = 0.7f),
                    ),
                )
            }
        }
    }
}

/**
 * The sleep timer as a pulldown: a 40dp pill showing the preset in force, which opens
 * the presets in place, under itself. In place rather than a floating menu, because the
 * video is a native surface that paints over any popup that strays onto it, and this
 * menu sits right next to the video.
 */
@Composable
private fun SleepTimerPulldown(minutes: Int, onSelect: (Int) -> Unit) {
    val c = PureTvTheme.colors
    var open by remember { mutableStateOf(false) }
    Column(Modifier.width(SLEEP_PULLDOWN_WIDTH), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val anchor = remember { MutableInteractionSource() }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .expressiveClickable(
                    interaction = anchor,
                    onClick = { open = !open },
                    restRadius = 20.dp,
                    hoverRadius = 12.dp,
                    color = if (minutes > 0) c.primaryContainer else c.surfaceHigh,
                    hoverColor = if (minutes > 0) c.primaryContainer else c.surfaceHighest,
                )
                .padding(start = 16.dp, end = 10.dp),
        ) {
            Text(
                SleepTimerText.preset(minutes),
                style = MaterialTheme.typography.labelLarge,
                color = if (minutes > 0) c.onPrimaryContainer else c.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (open) ExpressiveIcons.ExpandLess else ExpressiveIcons.ExpandMore,
                contentDescription = if (open) "Close sleep timer choices" else "Choose a sleep timer",
                tint = if (minutes > 0) c.onPrimaryContainer else c.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        if (open) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.surfaceHigh)
                    .padding(vertical = 4.dp),
            ) {
                SleepTimer.PRESETS_MINUTES.forEach { preset ->
                    val row = remember(preset) { MutableInteractionSource() }
                    val selected = preset == minutes
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .expressiveClickable(
                                interaction = row,
                                onClick = { onSelect(preset); open = false },
                                restRadius = 0.dp,
                                hoverRadius = 0.dp,
                                color = Color.Transparent,
                                hoverColor = c.surfaceHighest,
                            )
                            .padding(horizontal = 16.dp),
                    ) {
                        Text(
                            SleepTimerText.preset(preset),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) c.primary else c.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) Icon(ExpressiveIcons.Check, contentDescription = null, tint = c.primary, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Text("Press Z to cycle.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

private val SLEEP_PULLDOWN_WIDTH = 168.dp

/** What the menu's RTX Video section shows and changes. */
class RtxMenuState(
    val status: RtxVideoStatus,
    val superResolution: Boolean,
    val onSuperResolutionChange: (Boolean) -> Unit,
    // Null when the player can't do RTX Video HDR, which hides that switch.
    val hdr: Boolean?,
    val onHdrChange: (Boolean) -> Unit,
)

/**
 * The menu's audio normalizer switch. It (and the strength under it) changes the stream
 * that is playing: mpv swaps its audio filter, VLC starts the stream again on an
 * instance with that level (or without it), which takes a second or two.
 */
@Composable
private fun AudioNormalizerRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = PureTvTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Even out loud and quiet audio", style = MaterialTheme.typography.titleSmall, color = c.onSurface)
            Text(
                "Turns down sudden loud moments and lifts quiet voices. Press A to switch it while watching.",
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 640.dp),
            )
        }
        ExpressiveSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PlayerMenuSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Kicker(title)
        content()
    }
}
