package com.puretv.twitch.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.puretv.twitch.desktop.player.RtxHdrState
import com.puretv.twitch.desktop.player.RtxVideoStatus
import com.puretv.twitch.desktop.player.SuperResolutionState
import com.puretv.twitch.desktop.ui.theme.PureTvTheme
import com.puretv.twitch.desktop.ui.theme.PureTvType

/**
 * What the player's NVIDIA RTX Video status means, in words, for the in-player menu
 * and Settings. Pure, so the wording is tested. Every line says what the viewer can
 * do about it when there is something to do.
 */
object RtxVideoText {
    // Where Windows keeps the per-app GPU choice and the HDR switch.
    private const val GRAPHICS_SETTINGS = "Settings > System > Display > Graphics"
    private const val DISPLAY_SETTINGS = "Settings > System > Display"

    /**
     * The driver decides how much Super Resolution does: its RTX Video Enhancement switch
     * and quality level (1 to 4) live in the NVIDIA app, and with it off or at a low level
     * PureTV asking for Super Resolution changes little. Shown wherever the switch is.
     */
    const val DRIVER_HINT =
        "How strong it is comes from the NVIDIA app: System > Video > RTX Video Enhancement. " +
            "Turn Super Resolution on there and set quality to 4 for the sharpest picture."

    fun superResolution(s: RtxVideoStatus): String = when (s.superResolution) {
        SuperResolutionState.OFF -> "Off."
        SuperResolutionState.WAITING -> "Starts with the video."
        SuperResolutionState.ACTIVE -> {
            val from = s.sourceHeight?.let { "${it}p" }
            val to = s.outputHeight?.let { "${it}p" }
            if (from != null && to != null) "Working: upscaling $from to $to." else "Working."
        }
        SuperResolutionState.STANDBY ->
            "Ready. It kicks in when the video is shown bigger than it was sent" +
                (s.sourceHeight?.let { " (${it}p)" } ?: "") + ", like in fullscreen or a bigger window."
        SuperResolutionState.UNSUPPORTED -> wrongGpu(s) ?: "Your NVIDIA driver didn't accept it. Updating the driver usually fixes this."
    }

    fun hdr(s: RtxVideoStatus): String = when (s.hdr) {
        RtxHdrState.OFF -> "Off."
        RtxHdrState.WAITING -> "Starts with the video."
        RtxHdrState.ACTIVE -> "Working: the stream is shown in HDR."
        RtxHdrState.DISPLAY_SDR -> "Turn on HDR for this display in Windows ($DISPLAY_SETTINGS) to use it."
        RtxHdrState.UNSUPPORTED -> wrongGpu(s) ?: "Your GPU or driver didn't accept it. It needs a recent NVIDIA driver."
    }

    /** When Windows runs PureTV on another graphics chip, nothing RTX can work: say how to fix it. */
    private fun wrongGpu(s: RtxVideoStatus): String? =
        if (s.adapterIsNvidia == false) {
            "Windows is running PureTV on ${s.adapter ?: "another graphics chip"}. " +
                "Set PureTV to High performance in Windows ($GRAPHICS_SETTINGS), then restart PureTV."
        } else null
}

/**
 * The "RTX Video" section of the in-player menu: one switch each for Super Resolution
 * and HDR (when the player offers it, see [hdr]), with what each is doing right now
 * under it. Stateless.
 */
@Composable
fun RtxVideoMenuSection(
    status: RtxVideoStatus,
    superResolution: Boolean,
    onSuperResolutionChange: (Boolean) -> Unit,
    hdr: Boolean?,
    onHdrChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RtxSwitchRow("Super Resolution", superResolution, onSuperResolutionChange, RtxVideoText.superResolution(status))
        if (hdr != null) RtxSwitchRow("HDR", hdr, onHdrChange, RtxVideoText.hdr(status))
        if (superResolution) {
            Text(
                RtxVideoText.DRIVER_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = PureTvTheme.colors.outline,
                modifier = Modifier.widthIn(max = 640.dp),
            )
        }
    }
}

@Composable
private fun RtxSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, detail: String) {
    val c = PureTvTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = c.onSurface)
            // Tabular figures keep "upscaling 1080p to 1440p" steady while the window resizes.
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = PureTvType.tabular),
                color = c.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 640.dp),
            )
        }
        ExpressiveSwitch(checked = checked, onCheckedChange = onChange)
    }
}
