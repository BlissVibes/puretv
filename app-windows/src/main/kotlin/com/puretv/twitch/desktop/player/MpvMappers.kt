package com.puretv.twitch.desktop.player

import com.puretv.twitch.core.model.UpscalingMode
import java.util.Locale

/** What [MpvPlayer.attachToPanel] should do given the current binding state. */
internal enum class AttachAction { ATTACH, REATTACH, NOOP }

/**
 * Decide how to (re)bind mpv to a surface. mpv's `wid` is set once before
 * `mpv_initialize`, so a live context belongs to one Canvas/HWND: re-attaching
 * to a *different* surface requires tearing the old context down first, never
 * reusing one bound to a now-destroyed HWND. Pure + unit-tested.
 */
internal fun mpvAttachAction(initialized: Boolean, sameSurface: Boolean): AttachAction = when {
    !initialized -> AttachAction.ATTACH
    sameSurface -> AttachAction.NOOP
    else -> AttachAction.REATTACH
}

/** Verdict for the upscaling stats overlay: is the output bigger than the source? */
internal sealed interface UpscaleStatus {
    data class Upscaling(val factor: Double) : UpscaleStatus
    object Native : UpscaleStatus
    data class Downscaling(val factor: Double) : UpscaleStatus
}

/**
 * Compare a video source size against the actual output (VO surface) size to
 * decide whether scaling is happening, and by how much. Pure + unit-tested — the
 * honest core of the upscaling proof. `factor` is the linear height ratio;
 * non-positive (unknown) dimensions are reported as [UpscaleStatus.Native].
 */
internal fun upscaleSummary(srcW: Int, srcH: Int, outW: Int, outH: Int): UpscaleStatus {
    if (srcW <= 0 || srcH <= 0 || outW <= 0 || outH <= 0) return UpscaleStatus.Native
    val factor = outH.toDouble() / srcH.toDouble()
    return when {
        factor > 1.01 -> UpscaleStatus.Upscaling(factor)
        factor < 0.99 -> UpscaleStatus.Downscaling(factor)
        else -> UpscaleStatus.Native
    }
}

/**
 * Format the upscaling stats into mpv's OSD text (rendered by `show-text`).
 * Deliberately ASCII-only (no ×/→/· — keeps it safe across JNA string encoding)
 * and restrained: three terse lines, editorial not gamer-HUD. Pure + unit-tested.
 */
internal fun formatStatsOsd(stats: VideoStats, modeLabel: String): String {
    val verdict = when (val u = upscaleSummary(stats.sourceWidth, stats.sourceHeight, stats.outputWidth, stats.outputHeight)) {
        is UpscaleStatus.Upscaling -> "x%.2f upscale".format(Locale.US, u.factor)
        is UpscaleStatus.Downscaling -> "x%.2f downscale".format(Locale.US, u.factor)
        UpscaleStatus.Native -> "native"
    }
    val vo = stats.vo ?: "?"
    val hwdec = stats.hwdec ?: "sw"
    val fps = stats.fps?.let { "%.0f fps".format(Locale.US, it) } ?: "-- fps"
    val scaler = stats.scaler ?: "?"
    val shader = stats.shaderName?.let { " + $it" } ?: ""
    // Lead with the active upscaling mode so the overlay answers "is it on, and
    // which?" at a glance — the whole point of the proof.
    return buildString {
        appendLine("mpv | $modeLabel | $vo | $hwdec | $fps")
        appendLine("${stats.sourceWidth}x${stats.sourceHeight} -> ${stats.outputWidth}x${stats.outputHeight}    $verdict")
        append("scaler $scaler$shader")
    }
}

/** UI label for an upscaling mode (also the F3 overlay tag). */
internal fun upscaleModeLabel(mode: UpscalingMode): String = when (mode) {
    UpscalingMode.OFF -> "Off"
    UpscalingMode.STANDARD -> "Sharp"
    UpscalingMode.ANIME -> "Anime"
}

/** Resolved (forward-slashed) absolute paths to the bundled upscaling shaders.
 *  Any path may be blank if its file wasn't found — the mode degrades gracefully. */
internal data class ShaderPaths(val cas: String, val animePipeline: List<String>)

/** Join shader paths for mpv's `glsl-shaders` — a PATH list, so the separator is
 *  the OS path separator (';' on Windows). Blank paths are dropped. */
internal fun joinShaders(paths: List<String>): String =
    paths.filter { it.isNotBlank() }.joinToString(java.io.File.pathSeparator)

/**
 * Maps an [UpscalingMode] to the COMPLETE set of mpv scaler properties. Every mode
 * returns the SAME four keys (scale, cscale, dscale, glsl-shaders) so applying one
 * mode over another at runtime fully overwrites the previous scaler — `glsl-shaders`
 * is cleared to "" when a mode has no shader, which is what makes live switching
 * actually remove the previous mode's shaders. Content-matched for a mixed Twitch
 * diet: STANDARD ("Sharp") = ewa_lanczossharp + CAS for live-action; ANIME = the
 * full Anime4K Mode-A pipeline. A missing shader file (blank path) degrades to the
 * base scaler rather than a broken path. Pure + unit-tested; used at init
 * (set_option) and live re-apply (set_property).
 */
internal fun mpvScalerProps(mode: UpscalingMode, shaders: ShaderPaths): Map<String, String> = when (mode) {
    UpscalingMode.OFF -> mapOf("scale" to "bilinear", "cscale" to "bilinear", "dscale" to "bilinear", "glsl-shaders" to "")
    UpscalingMode.STANDARD -> mapOf(
        "scale" to "ewa_lanczossharp", "cscale" to "ewa_lanczossharp", "dscale" to "mitchell",
        "glsl-shaders" to joinShaders(listOf(shaders.cas)),
    )
    UpscalingMode.ANIME -> mapOf(
        "scale" to "ewa_lanczossharp", "cscale" to "ewa_lanczossharp", "dscale" to "mitchell",
        "glsl-shaders" to joinShaders(shaders.animePipeline),
    )
}

/** Apply one observed mpv property change (read as a string) to a [PlayerStatus].
 *  Pure + testable; unknown property names pass through unchanged. */
internal fun mpvApply(s: PlayerStatus, name: String, value: String?): PlayerStatus = when (name) {
    "time-pos" -> s.copy(positionMs = ((value?.toDoubleOrNull() ?: 0.0) * 1000).toLong())
    "duration" -> s.copy(durationMs = ((value?.toDoubleOrNull() ?: 0.0) * 1000).toLong())
    "pause" -> s.copy(isPlaying = value == "no")
    "core-idle" -> s.copy(isBuffering = value == "yes")
    "paused-for-cache" -> s.copy(isBuffering = value == "yes")
    "seekable" -> s.copy(isSeekable = value == "yes")
    else -> s
}

// NVIDIA RTX Video on mpv. mpv drives both features through its d3d11vpp video
// filter (mpv manual, VIDEO FILTERS, d3d11vpp): `scaling-mode=nvidia` turns on RTX
// Super Resolution and `scale=<factor>` sets how much larger the filter's output is
// (at 1.0 nothing is upscaled); `nvidia-true-hdr` turns on RTX Video HDR (output
// tagged BT.2020 PQ). The filter needs mpv's Direct3D 11 GPU context (gpu-api=d3d11;
// under Vulkan it fails to create). Checked against the bundled libmpv
// (v0.41.0-744): "NVIDIA RTX Super Resolution enabled." with video-out-params at 2x,
// and gamma pq with nvidia-true-hdr, on an RTX 4070.

/** The hwdec mpv normally uses. */
internal const val MPV_HWDEC_DEFAULT = "auto-safe"

/** d3d11 decoding that keeps frames on the GPU, so d3d11vpp gets them without a copy. */
internal const val MPV_HWDEC_RTX = "d3d11va"

/** The largest Super Resolution factor we ask for; past it mpv's scaler does the rest. */
internal const val RTX_MAX_SCALE = 4.0

/** Below this the video is close enough to its own size that there is nothing to upscale. */
internal const val RTX_MIN_SCALE = 1.05

/** A new factor within this relative change keeps the old one (no filter rebuild). */
internal const val RTX_SCALE_DEADBAND = 0.05

/** The hwdec for a choice: frames stay on the GPU while RTX Video is asked for. */
internal fun mpvRtxHwdec(choice: RtxVideoChoice): String = if (choice.any) MPV_HWDEC_RTX else MPV_HWDEC_DEFAULT

/**
 * The Super Resolution factor for a source of [srcW]x[srcH] drawn in [drawnW]x[drawnH]
 * (the video area without black bars): the ratio that fits, capped at [RTX_MAX_SCALE],
 * rounded to 2 decimals. 1.0 when a size is unknown or the video is not drawn
 * meaningfully larger than its source (RTX VSR only upscales).
 */
internal fun rtxScale(srcW: Int, srcH: Int, drawnW: Int, drawnH: Int): Double {
    if (srcW <= 0 || srcH <= 0 || drawnW <= 0 || drawnH <= 0) return 1.0
    val fit = minOf(drawnW.toDouble() / srcW, drawnH.toDouble() / srcH)
    if (fit < RTX_MIN_SCALE) return 1.0
    return Math.round(minOf(fit, RTX_MAX_SCALE) * 100) / 100.0
}

/**
 * The factor to use now, given the one in place: small changes (a window dragged a
 * few pixels) keep [current], since every new factor rebuilds the filter.
 */
internal fun rtxScaleNext(current: Double, next: Double): Double = when {
    (current <= 1.0) != (next <= 1.0) -> next
    current <= 1.0 -> 1.0
    Math.abs(next - current) / current > RTX_SCALE_DEADBAND -> next
    else -> current
}

/**
 * The mpv `vf` value for a choice: "" when there is nothing for the filter to do.
 * Super Resolution only when [scale] is above 1. HDR only once the display is known to
 * be in HDR mode ([displayHdr] true). On an SDR display mpv tone-maps the 1000-nit PQ
 * output straight back down, which blows out highlights and over-saturates colour (seen
 * on the owner's SDR monitor), so "not known yet" counts as no. Pure + unit-tested.
 */
internal fun mpvRtxFilter(choice: RtxVideoChoice, scale: Double, displayHdr: Boolean?): String {
    val sr = choice.superResolution && scale > 1.0
    val hdr = choice.hdr && displayHdr == true
    if (!sr && !hdr) return ""
    val opts = buildList {
        if (sr) {
            add("scaling-mode=nvidia")
            add("scale=" + String.format(Locale.US, "%.2f", scale))
        }
        if (hdr) add("nvidia-true-hdr=yes")
    }
    return "d3d11vpp=" + opts.joinToString(":")
}

/**
 * RTX vs the Scaling choice: while RTX Super Resolution is on, it takes priority and the
 * shader upscalers (CAS for Sharp, Anime4K for Anime) are left out, since they would run
 * on a frame RTX already upscaled and sharpened, fight its look and cost a lot of GPU.
 * The mode's base scaler stays for whatever is left (rounding, past the 4x cap, or a
 * window smaller than the source). This does not follow ACTIVE vs STANDBY, so resizing
 * the window never loads and unloads shaders. When Super Resolution is off, or failed,
 * the Scaling choice applies as before.
 */
internal fun mpvScalerPropsWithRtx(mode: UpscalingMode, shaders: ShaderPaths, rtxSuperResolution: Boolean): Map<String, String> {
    val props = mpvScalerProps(mode, shaders)
    return if (rtxSuperResolution) props + ("glsl-shaders" to "") else props
}

/** The F3 overlay tag: RTX Super Resolution when it has taken over from the Scaling choice. */
internal fun mpvScaleLabel(mode: UpscalingMode, rtxSuperResolution: Boolean): String =
    if (rtxSuperResolution) "RTX VSR" else upscaleModeLabel(mode)

/** What mpv says about the video, as far as RTX Video is concerned. 0 / null = not known. */
internal data class MpvRtxObserved(
    /** The decoded video (video-params), before any filter. */
    val srcW: Int = 0,
    val srcH: Int = 0,
    /** The source's display size (video-params dw/dh), what the scale is worked out from. */
    val srcDisplayW: Int = 0,
    val srcDisplayH: Int = 0,
    /** After the filters (video-out-params). */
    val outW: Int = 0,
    val outH: Int = 0,
    /** The area the video is drawn in (osd-dimensions without the black bars). */
    val drawnW: Int = 0,
    val drawnH: Int = 0,
    val sourceGamma: String? = null,
    val outGamma: String? = null,
    /** What the VO renders to (video-target-params); pq when the display is in HDR mode. */
    val targetGamma: String? = null,
) {
    val videoOpen: Boolean get() = srcW > 0 && srcH > 0

    /** True when the display takes HDR, false when it is SDR, null before the VO says. */
    val displayHdr: Boolean? get() = targetGamma?.let { isHdrGamma(it) }

    val scale: Double get() = rtxScale(
        srcDisplayW.takeIf { it > 0 } ?: srcW,
        srcDisplayH.takeIf { it > 0 } ?: srcH,
        drawnW, drawnH,
    )
}

private fun isHdrGamma(gamma: String): Boolean = gamma == "pq" || gamma == "hlg"

private fun jsonInt(json: String?, key: String): Int =
    json?.let { Regex("\"${Regex.escape(key)}\":(-?[0-9.]+)").find(it) }
        ?.groupValues?.get(1)?.toDoubleOrNull()?.toInt() ?: 0

private fun jsonString(json: String?, key: String): String? =
    json?.let { Regex("\"${Regex.escape(key)}\":\"([^\"]*)\"").find(it) }?.groupValues?.get(1)

/**
 * Reads mpv's JSON property strings (video-params, video-out-params,
 * video-target-params, osd-dimensions) into an [MpvRtxObserved]. Missing or null
 * properties read as unknown. Pure + unit-tested.
 */
internal fun mpvRtxObserve(videoParams: String?, outParams: String?, targetParams: String?, osdDimensions: String?): MpvRtxObserved =
    MpvRtxObserved(
        srcW = jsonInt(videoParams, "w"),
        srcH = jsonInt(videoParams, "h"),
        srcDisplayW = jsonInt(videoParams, "dw"),
        srcDisplayH = jsonInt(videoParams, "dh"),
        outW = jsonInt(outParams, "w"),
        outH = jsonInt(outParams, "h"),
        drawnW = (jsonInt(osdDimensions, "w") - jsonInt(osdDimensions, "ml") - jsonInt(osdDimensions, "mr")).coerceAtLeast(0),
        drawnH = (jsonInt(osdDimensions, "h") - jsonInt(osdDimensions, "mt") - jsonInt(osdDimensions, "mb")).coerceAtLeast(0),
        sourceGamma = jsonString(videoParams, "gamma"),
        outGamma = jsonString(outParams, "gamma"),
        targetGamma = jsonString(targetParams, "gamma"),
    )

/** What an mpv warning or error line says about RTX Video. */
internal enum class MpvRtxLog { FILTER_FAILED, SUPER_RESOLUTION_FAILED, HDR_FAILED }

/**
 * Picks the d3d11vpp failures out of mpv's log (warn level and up). The wording is
 * mpv's own (video/filter/vf_d3d11vpp.c and the filter wrapper); the first was seen
 * live with the bundled build under a Vulkan context. Anything else is null, including
 * the HDR "only a guess" max-luma warning mpv prints every time HDR is on.
 */
internal fun mpvRtxLog(prefix: String?, text: String): MpvRtxLog? = when {
    text.contains("Creating filter 'd3d11vpp' failed") -> MpvRtxLog.FILTER_FAILED
    prefix == "d3d11vpp" && text.startsWith("Failed to enable NVIDIA RTX Super Resolution") -> MpvRtxLog.SUPER_RESOLUTION_FAILED
    prefix == "d3d11vpp" && (text.startsWith("NVIDIA RTX Video HDR not supported") ||
        text.startsWith("Failed to enable NVIDIA RTX Video HDR")) -> MpvRtxLog.HDR_FAILED
    else -> null
}

/** What the player has learned about the d3d11vpp filter on this context. */
internal data class MpvRtxHealth(
    val filterFailed: Boolean = false,
    val superResolutionFailed: Boolean = false,
    val hdrFailed: Boolean = false,
) {
    fun with(log: MpvRtxLog): MpvRtxHealth = when (log) {
        MpvRtxLog.FILTER_FAILED -> copy(filterFailed = true)
        MpvRtxLog.SUPER_RESOLUTION_FAILED -> copy(superResolutionFailed = true)
        MpvRtxLog.HDR_FAILED -> copy(hdrFailed = true)
    }
}

/**
 * True when the filter in place ([filter], "" for none) has had time to work
 * ([settled]) and the output shows neither of its effects: no larger frame and no PQ.
 * That is how a filter mpv dropped looks (it still lists it in `vf`). Pure + unit-tested.
 */
internal fun mpvRtxFilterIneffective(filter: String, seen: MpvRtxObserved, settled: Boolean): Boolean {
    if (!settled || filter.isEmpty() || !seen.videoOpen || seen.outW <= 0) return false
    val wantsSr = filter.contains("scaling-mode=nvidia")
    val wantsHdr = filter.contains("nvidia-true-hdr")
    val upscaled = seen.outW > seen.srcW || seen.outH > seen.srcH
    val hdrOut = seen.outGamma == "pq"
    return !(wantsSr && upscaled) && !(wantsHdr && hdrOut)
}

/**
 * The [RtxVideoStatus] for a choice, the filter in place, what mpv reports and the
 * failures seen. [settled] is true once the filter has had time to reach the output;
 * before that a missing effect reads as WAITING, not UNSUPPORTED. Pure + unit-tested.
 */
internal fun mpvRtxStatus(
    choice: RtxVideoChoice,
    filter: String,
    seen: MpvRtxObserved,
    health: MpvRtxHealth,
    settled: Boolean,
): RtxVideoStatus {
    val srInFilter = filter.contains("scaling-mode=nvidia")
    val hdrInFilter = filter.contains("nvidia-true-hdr")
    val sr = when {
        !choice.superResolution -> SuperResolutionState.OFF
        health.filterFailed || health.superResolutionFailed -> SuperResolutionState.UNSUPPORTED
        !seen.videoOpen -> SuperResolutionState.WAITING
        !srInFilter -> if (seen.drawnW > 0 && seen.scale <= 1.0) SuperResolutionState.STANDBY else SuperResolutionState.WAITING
        seen.outW > seen.srcW || seen.outH > seen.srcH -> SuperResolutionState.ACTIVE
        settled -> SuperResolutionState.UNSUPPORTED
        else -> SuperResolutionState.WAITING
    }
    val hdr = when {
        !choice.hdr -> RtxHdrState.OFF
        health.filterFailed || health.hdrFailed -> RtxHdrState.UNSUPPORTED
        !seen.videoOpen -> RtxHdrState.WAITING
        seen.displayHdr == false -> RtxHdrState.DISPLAY_SDR
        // An HDR source: RTX Video HDR has nothing to convert and mpv skips it.
        seen.sourceGamma?.let { isHdrGamma(it) } == true -> RtxHdrState.OFF
        !hdrInFilter -> RtxHdrState.WAITING
        seen.outGamma == "pq" -> if (seen.displayHdr == true) RtxHdrState.ACTIVE else RtxHdrState.WAITING
        settled -> RtxHdrState.UNSUPPORTED
        else -> RtxHdrState.WAITING
    }
    return RtxVideoStatus(
        superResolution = sr,
        hdr = hdr,
        sourceHeight = seen.srcH.takeIf { it > 0 },
        outputHeight = seen.drawnH.takeIf { it > 0 },
    )
}
