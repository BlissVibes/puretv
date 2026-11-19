package com.puretv.twitch.desktop.player

import com.puretv.twitch.core.model.UpscalingMode
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvRtxVideoTest {
    private val both = RtxVideoChoice(superResolution = true, hdr = true)
    private val srOnly = RtxVideoChoice(superResolution = true, hdr = false)
    private val hdrOnly = RtxVideoChoice(superResolution = false, hdr = true)

    // --- vf string ---

    @Test fun filterForEachChoice() {
        assertEquals("d3d11vpp=scaling-mode=nvidia:scale=2.00:nvidia-true-hdr=yes", mpvRtxFilter(both, 2.0, displayHdr = true))
        assertEquals("d3d11vpp=scaling-mode=nvidia:scale=1.33", mpvRtxFilter(srOnly, 1.33, displayHdr = true))
        assertEquals("d3d11vpp=nvidia-true-hdr=yes", mpvRtxFilter(hdrOnly, 2.0, displayHdr = true))
        assertEquals("", mpvRtxFilter(RtxVideoChoice.NONE, 2.0, displayHdr = true))
    }

    @Test fun noSuperResolutionAtScaleOne() {
        // RTX VSR only upscales: at 1x there is nothing for it, so only HDR (if any) stays.
        assertEquals("", mpvRtxFilter(srOnly, 1.0, displayHdr = true))
        assertEquals("d3d11vpp=nvidia-true-hdr=yes", mpvRtxFilter(both, 1.0, displayHdr = true))
    }

    @Test fun hdrOnlyOnADisplayKnownToBeInHdrMode() {
        assertEquals("d3d11vpp=scaling-mode=nvidia:scale=2.00", mpvRtxFilter(both, 2.0, displayHdr = false))
        assertEquals("", mpvRtxFilter(hdrOnly, 2.0, displayHdr = false))
        // Not known yet counts as SDR: PQ tone-mapped onto an SDR display blows the picture out.
        assertEquals("", mpvRtxFilter(hdrOnly, 2.0, displayHdr = null))
        assertEquals("d3d11vpp=scaling-mode=nvidia:scale=2.00", mpvRtxFilter(both, 2.0, displayHdr = null))
    }

    @Test fun scaleUsesADotInEveryLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("d3d11vpp=scaling-mode=nvidia:scale=1.50", mpvRtxFilter(srOnly, 1.5, displayHdr = null))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun hwdecKeepsFramesOnTheGpuOnlyWhenAsked() {
        assertEquals("d3d11va", mpvRtxHwdec(both))
        assertEquals("d3d11va", mpvRtxHwdec(hdrOnly))
        assertEquals("auto-safe", mpvRtxHwdec(RtxVideoChoice.NONE))
    }

    // --- scale ---

    @Test fun scaleIsTheFitRatio() {
        assertEquals(2.0, rtxScale(1280, 720, 2560, 1440))
        assertEquals(1.33, rtxScale(1920, 1080, 2560, 1440))
        // Letterboxed: the smaller ratio wins.
        assertEquals(2.0, rtxScale(1280, 720, 3000, 1440))
    }

    @Test fun scaleIsCappedAndNeverBelowOne() {
        assertEquals(RTX_MAX_SCALE, rtxScale(320, 180, 3840, 2160))
        assertEquals(1.0, rtxScale(1920, 1080, 1280, 720))
        assertEquals(1.0, rtxScale(1920, 1080, 1960, 1102)) // under 1.05: not worth it
        assertEquals(1.0, rtxScale(0, 0, 2560, 1440))
        assertEquals(1.0, rtxScale(1280, 720, 0, 0))
    }

    @Test fun smallScaleChangesKeepTheFilter() {
        assertEquals(2.0, rtxScaleNext(2.0, 2.05))
        assertEquals(2.4, rtxScaleNext(2.0, 2.4))
        assertEquals(1.0, rtxScaleNext(2.0, 1.0)) // into standby always applies
        assertEquals(1.1, rtxScaleNext(1.0, 1.1)) // out of standby always applies
        assertEquals(1.0, rtxScaleNext(1.0, 1.0))
    }

    // --- RTX vs Scaling ---

    @Test fun superResolutionDropsTheShadersButKeepsTheBaseScaler() {
        val shaders = ShaderPaths(cas = "C:/s/CAS.glsl", animePipeline = listOf("C:/s/A.glsl", "C:/s/B.glsl"))
        for (mode in UpscalingMode.entries) {
            val p = mpvScalerPropsWithRtx(mode, shaders, rtxSuperResolution = true)
            assertEquals("", p["glsl-shaders"], "$mode under RTX VSR")
            assertEquals(mpvScalerProps(mode, shaders)["scale"], p["scale"])
        }
        // Off (or failed): the Scaling choice as it was.
        for (mode in UpscalingMode.entries) {
            assertEquals(mpvScalerProps(mode, shaders), mpvScalerPropsWithRtx(mode, shaders, rtxSuperResolution = false))
        }
    }

    @Test fun overlayLabel() {
        assertEquals("RTX VSR", mpvScaleLabel(UpscalingMode.ANIME, rtxSuperResolution = true))
        assertEquals("Anime", mpvScaleLabel(UpscalingMode.ANIME, rtxSuperResolution = false))
    }

    // --- reading mpv ---

    // Shapes as the bundled libmpv printed them during the RTX 4070 check.
    private val videoParams = """{"pixelformat":"d3d11","hw-pixelformat":"nv12","w":640,"h":360,"dw":640,"dh":360,"crop-x":0,"crop-y":0,"crop-w":640,"crop-h":360,"primaries":"bt.709","gamma":"bt.1886"}"""
    private val outSr = """{"pixelformat":"d3d11","w":1280,"h":720,"dw":1280,"dh":720,"crop-w":1280,"crop-h":720,"gamma":"bt.1886"}"""
    private val outSrHdr = """{"pixelformat":"d3d11","hw-pixelformat":"x2bgr10","w":1280,"h":720,"dw":1280,"dh":720,"primaries":"bt.2020","gamma":"pq"}"""
    private val outPlain = """{"pixelformat":"d3d11","w":640,"h":360,"dw":640,"dh":360,"gamma":"bt.1886"}"""
    private val targetHdr = """{"pixelformat":"rgb10a2","w":1280,"h":720,"primaries":"bt.2020","gamma":"pq"}"""
    private val targetSdr = """{"pixelformat":"rgb10a2","w":1280,"h":720,"primaries":"bt.709","gamma":"srgb"}"""
    private val osd = """{"w":2560,"h":1369,"par":1.000000,"aspect":1.869978,"mt":0,"mb":0,"ml":63,"mr":64}"""

    @Test fun readsMpvJson() {
        val seen = mpvRtxObserve(videoParams, outSrHdr, targetHdr, osd)
        assertEquals(640, seen.srcW)
        assertEquals(360, seen.srcH)
        assertEquals(1280, seen.outW)
        assertEquals(720, seen.outH)
        assertEquals(2433, seen.drawnW) // 2560 - 63 - 64
        assertEquals(1369, seen.drawnH)
        assertEquals("bt.1886", seen.sourceGamma)
        assertEquals("pq", seen.outGamma)
        assertEquals(true, seen.displayHdr)
        assertEquals(3.8, seen.scale)
        assertEquals(false, mpvRtxObserve(videoParams, outPlain, targetSdr, osd).displayHdr)
    }

    @Test fun missingPropertiesReadAsUnknown() {
        val seen = mpvRtxObserve(null, null, null, null)
        assertFalse(seen.videoOpen)
        assertNull(seen.displayHdr)
        assertEquals(1.0, seen.scale)
    }

    // --- status ---

    private fun status(choice: RtxVideoChoice, filter: String, seen: MpvRtxObserved, health: MpvRtxHealth = MpvRtxHealth(), settled: Boolean = true) =
        mpvRtxStatus(choice, filter, seen, health, settled)

    @Test fun offWhenNotAskedFor() {
        val s = status(RtxVideoChoice.NONE, "", mpvRtxObserve(videoParams, outPlain, targetHdr, osd))
        assertEquals(SuperResolutionState.OFF, s.superResolution)
        assertEquals(RtxHdrState.OFF, s.hdr)
    }

    @Test fun waitingBeforeVideo() {
        val s = status(both, "", MpvRtxObserved(), settled = false)
        assertEquals(SuperResolutionState.WAITING, s.superResolution)
        assertEquals(RtxHdrState.WAITING, s.hdr)
    }

    @Test fun activeWhenTheOutputIsLargerAndPq() {
        val f = "d3d11vpp=scaling-mode=nvidia:scale=2.00:nvidia-true-hdr=yes"
        val s = status(both, f, mpvRtxObserve(videoParams, outSrHdr, targetHdr, osd))
        assertEquals(SuperResolutionState.ACTIVE, s.superResolution)
        assertEquals(RtxHdrState.ACTIVE, s.hdr)
        assertEquals(360, s.sourceHeight)
        assertEquals(1369, s.outputHeight)
    }

    @Test fun standbyWhenNotShownLarger() {
        val small = """{"w":1280,"h":720,"ml":0,"mr":0,"mt":0,"mb":0}"""
        val big = """{"pixelformat":"d3d11","w":1920,"h":1080,"dw":1920,"dh":1080,"gamma":"bt.1886"}"""
        val s = status(srOnly, "", mpvRtxObserve(big, big, targetHdr, small))
        assertEquals(SuperResolutionState.STANDBY, s.superResolution)
    }

    @Test fun displaySdr() {
        val f = "d3d11vpp=scaling-mode=nvidia:scale=2.00"
        val s = status(both, f, mpvRtxObserve(videoParams, outSr, targetSdr, osd))
        assertEquals(SuperResolutionState.ACTIVE, s.superResolution)
        assertEquals(RtxHdrState.DISPLAY_SDR, s.hdr)
    }

    @Test fun missingEffectIsWaitingThenUnsupported() {
        val f = "d3d11vpp=scaling-mode=nvidia:scale=2.00:nvidia-true-hdr=yes"
        val seen = mpvRtxObserve(videoParams, outPlain, targetHdr, osd)
        val early = status(both, f, seen, settled = false)
        assertEquals(SuperResolutionState.WAITING, early.superResolution)
        assertEquals(RtxHdrState.WAITING, early.hdr)
        val late = status(both, f, seen, settled = true)
        assertEquals(SuperResolutionState.UNSUPPORTED, late.superResolution)
        assertEquals(RtxHdrState.UNSUPPORTED, late.hdr)
    }

    @Test fun failuresFromTheLog() {
        val seen = mpvRtxObserve(videoParams, outSrHdr, targetHdr, osd)
        val f = "d3d11vpp=scaling-mode=nvidia:scale=2.00:nvidia-true-hdr=yes"
        val all = status(both, f, seen, MpvRtxHealth(filterFailed = true))
        assertEquals(SuperResolutionState.UNSUPPORTED, all.superResolution)
        assertEquals(RtxHdrState.UNSUPPORTED, all.hdr)
        val hdrOnlyFailed = status(both, f, seen, MpvRtxHealth(hdrFailed = true))
        assertEquals(SuperResolutionState.ACTIVE, hdrOnlyFailed.superResolution)
        assertEquals(RtxHdrState.UNSUPPORTED, hdrOnlyFailed.hdr)
    }

    @Test fun logLines() {
        assertEquals(MpvRtxLog.FILTER_FAILED, mpvRtxLog("user_filter_wrapper", "Creating filter 'd3d11vpp' failed.\n"))
        assertEquals(MpvRtxLog.SUPER_RESOLUTION_FAILED, mpvRtxLog("d3d11vpp", "Failed to enable NVIDIA RTX Super Resolution: E_FAIL\n"))
        assertEquals(MpvRtxLog.HDR_FAILED, mpvRtxLog("d3d11vpp", "NVIDIA RTX Video HDR not supported.\n"))
        assertEquals(MpvRtxLog.HDR_FAILED, mpvRtxLog("d3d11vpp", "Failed to enable NVIDIA RTX Video HDR: E_FAIL\n"))
        assertNull(mpvRtxLog("d3d11vpp", "Tagging image output as HDR with max-luma=1000 nits for NVIDIA RTX Video HDR. This is only a guess."))
        assertNull(mpvRtxLog("ffmpeg", "Failed to open segment"))
        assertEquals(MpvRtxHealth(hdrFailed = true), MpvRtxHealth().with(MpvRtxLog.HDR_FAILED))
    }

    @Test fun droppedFilterIsIneffectiveOnlyOnceSettled() {
        val f = "d3d11vpp=scaling-mode=nvidia:scale=2.00"
        val dropped = mpvRtxObserve(videoParams, outPlain, targetHdr, osd)
        assertFalse(mpvRtxFilterIneffective(f, dropped, settled = false))
        assertTrue(mpvRtxFilterIneffective(f, dropped, settled = true))
        assertFalse(mpvRtxFilterIneffective(f, mpvRtxObserve(videoParams, outSr, targetHdr, osd), settled = true))
        // HDR alone working is enough to keep the filter.
        assertFalse(mpvRtxFilterIneffective("d3d11vpp=nvidia-true-hdr=yes", mpvRtxObserve(videoParams, """{"w":640,"h":360,"gamma":"pq"}""", targetHdr, osd), settled = true))
        assertFalse(mpvRtxFilterIneffective("", dropped, settled = true))
    }
}
