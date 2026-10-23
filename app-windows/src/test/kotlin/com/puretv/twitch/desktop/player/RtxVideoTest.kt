package com.puretv.twitch.desktop.player

import com.puretv.twitch.core.model.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RtxVideoTest {
    private val both = RtxVideoChoice(superResolution = true, hdr = true)

    @Test fun argsFollowTheChoice() {
        assertEquals(listOf("--d3d11-upscale-mode=super", "--d3d11-hdr-mode=generate"), both.vlcArgs())
        assertEquals(listOf("--d3d11-upscale-mode=super"), RtxVideoChoice(true, false).vlcArgs())
        assertEquals(listOf("--d3d11-hdr-mode=generate"), RtxVideoChoice(false, true).vlcArgs())
        assertEquals(emptyList(), RtxVideoChoice.NONE.vlcArgs())
        assertFalse(RtxVideoChoice.NONE.any)
    }

    @Test fun vlcGetsHdrOnlyOnAnHdrDisplayAndASafeVlc() {
        assertEquals(both, both.forVlc(displayHdr = true, vlcHdrSafe = true))
        assertEquals(RtxVideoChoice(true, false), both.forVlc(displayHdr = false, vlcHdrSafe = true), "SDR display: tone-mapped back down")
        assertEquals(RtxVideoChoice(true, false), both.forVlc(displayHdr = true, vlcHdrSafe = false), "3.0.22/3.0.23 crash")
        assertFalse(RtxVideoChoice(false, true).forVlc(displayHdr = false, vlcHdrSafe = true).any, "HDR alone on SDR needs no RTX instance")
    }

    @Test fun vlcVersionsWhoseHdrModeRunsSafely() {
        assertTrue(vlcHdrSafe("3.0.24 Vetinari"))
        assertTrue(vlcHdrSafe("3.0.24-rc1 Vetinari"), "the official 3.0.24 build reports rc1")
        assertTrue(vlcHdrSafe("3.0.25 Vetinari"))
        assertTrue(vlcHdrSafe("3.0.21 Vetinari"))
        assertTrue(vlcHdrSafe("3.1.0 Something"))
        assertFalse(vlcHdrSafe("3.0.23 Vetinari"), "crashes")
        assertFalse(vlcHdrSafe("3.0.22 Vetinari"), "crashes")
        assertFalse(vlcHdrSafe("3.0.20 Vetinari"), "no HDR mode")
        assertFalse(vlcHdrSafe(null))
        assertFalse(vlcHdrSafe("garbage"))
    }

    @Test fun withoutAnRtxCardNothingIsAskedFor() {
        assertEquals(RtxVideoChoice.NONE, RtxVideoChoice.from(AppSettings(), rtxGpu = false))
        assertEquals(
            RtxVideoChoice(superResolution = true, hdr = false),
            RtxVideoChoice.from(AppSettings(), rtxGpu = true),
            "Super Resolution on by default for RTX owners, HDR their choice",
        )
        assertEquals(
            RtxVideoChoice(superResolution = false, hdr = true),
            RtxVideoChoice.from(AppSettings(rtxSuperResolution = false, rtxHdr = true), rtxGpu = true),
        )
    }

    // The lines below are what VLC 3.0.23 logged playing a live Twitch stream on an RTX 4070.
    private fun RtxLogReader.readAll(vararg lines: Pair<String, String>) = lines.forEach { (m, msg) -> read(m, msg) }

    private val selected = "direct3d11" to "Using Super Resolution scaler with B8G8R8A8 output"
    private val sdrOutput = "direct3d11" to "using colorspace RGB Rec.709 gamma:22 range:FULL"
    private val nvidiaDecoder = "avcodec" to
        "Using D3D11VA (NVIDIA GeForce RTX 4070, vendor 10de(NVIDIA), device 2786, revision a1) for hardware decoding"

    @Test fun superResolutionWorksOnlyWhenEnlarging() {
        val r = RtxLogReader(both)
        assertEquals(SuperResolutionState.WAITING, r.status().superResolution)
        r.readAll(selected, nvidiaDecoder)
        assertEquals(SuperResolutionState.WAITING, r.status().superResolution, "no sizes yet")
        r.sized(1920, 1080, 2560, 1440)
        val active = r.status()
        assertEquals(SuperResolutionState.ACTIVE, active.superResolution)
        assertEquals(1080, active.sourceHeight)
        assertEquals(1440, active.outputHeight)
        r.sized(852, 480, 710, 400)
        assertEquals(SuperResolutionState.STANDBY, r.status().superResolution, "a smaller picture is a downscale")
        r.sized(1920, 1080, 1920, 1080)
        assertEquals(SuperResolutionState.STANDBY, r.status().superResolution, "same size is not an upscale")
    }

    @Test fun refusalsAreReported() {
        val r = RtxLogReader(both)
        r.readAll("direct3d11" to "Super Resolution filter not supported", "direct3d11" to "Failed to enable NVIDIA True HDR")
        r.sized(1280, 720, 2560, 1440)
        assertEquals(SuperResolutionState.UNSUPPORTED, r.status().superResolution)
        assertEquals(RtxHdrState.UNSUPPORTED, r.status().hdr)
        val r2 = RtxLogReader(both)
        r2.read("direct3d11", "True HDR not supported")
        assertEquals(RtxHdrState.UNSUPPORTED, r2.status().hdr)
    }

    @Test fun hdrFollowsTheOutputColorspace() {
        val r = RtxLogReader(both)
        assertEquals(RtxHdrState.WAITING, r.status().hdr)
        r.readAll(sdrOutput)
        assertEquals(RtxHdrState.DISPLAY_SDR, r.status().hdr)
        r.read("direct3d11", "using colorspace RGB Rec.2020 gamma:2084 range:FULL")
        assertEquals(RtxHdrState.ACTIVE, r.status().hdr)
    }

    @Test fun offChoicesStayOff() {
        val r = RtxLogReader(RtxVideoChoice(superResolution = false, hdr = false))
        r.readAll(selected, sdrOutput)
        r.sized(1280, 720, 2560, 1440)
        assertEquals(SuperResolutionState.OFF, r.status().superResolution)
        assertEquals(RtxHdrState.OFF, r.status().hdr)
    }

    @Test fun theDecodingGpuIsNamed() {
        val r = RtxLogReader(both)
        r.readAll(nvidiaDecoder)
        assertEquals("NVIDIA GeForce RTX 4070", r.status().adapter)
        assertEquals(true, r.status().adapterIsNvidia)
        r.read("avcodec", "Using D3D11VA (AMD Radeon(TM) Graphics, vendor 1002(ATI), device 164e, revision cb) for hardware decoding")
        assertEquals("AMD Radeon(TM) Graphics", r.status().adapter)
        assertEquals(false, r.status().adapterIsNvidia)
    }

    @Test fun otherLinesChangeNothing() {
        val r = RtxLogReader(both)
        assertFalse(r.read("adaptive", "Retrieving https://example.ttvnw.net/segment.mp4"))
        assertFalse(r.read("direct3d11", "Created the D3D11 device type 1 level b100."))
        assertTrue(r.read(selected.first, selected.second))
        assertNull(r.status().adapter)
    }

    @Test fun anOlderVlcThatRefusesTheArgumentReadsAsUnsupported() {
        val r = RtxLogReader(both.forVlc(displayHdr = false, vlcHdrSafe = true))
        r.refused()
        r.sized(1280, 720, 2560, 1440)
        assertEquals(SuperResolutionState.UNSUPPORTED, r.status().superResolution)
    }

    @Test fun settledOnceTheVerdictAndGpuAreKnown() {
        val r = RtxLogReader(both.forVlc(displayHdr = false, vlcHdrSafe = true))
        assertFalse(r.settled)
        r.readAll(selected)
        assertFalse(r.settled, "the GPU is not known yet")
        r.readAll(nvidiaDecoder)
        assertTrue(r.settled)
    }

    @Test fun fittedSizeLetterboxes() {
        assertEquals(2560 to 1440, fittedSize(1920, 1080, 2560.0, 1440.0))
        assertEquals(2560 to 1440, fittedSize(1920, 1080, 3440.0, 1440.0), "ultrawide: pillarboxed, height limits")
        assertEquals(1280 to 720, fittedSize(1920, 1080, 1280.0, 1000.0), "tall area: width limits")
        assertEquals(0 to 0, fittedSize(0, 0, 1280.0, 720.0))
    }

    @Test fun statsOverlaySaysWhatIsScalingAndBy() {
        val text = vlcStatsOsd(
            RtxVideoStatus(SuperResolutionState.ACTIVE, RtxHdrState.ACTIVE, "NVIDIA GeForce RTX 4070", true, 1080, 1440),
            1920, 1080, 2560, 1440, picturesLost = 3,
        )
        val lines = text.lines()
        assertEquals("VLC | RTX Super Resolution | RTX HDR on", lines[0])
        assertEquals("1920x1080 -> 2560x1440    x1.33 upscale", lines[1])
        assertEquals("GPU NVIDIA GeForce RTX 4070 | dropped frames 3", lines[2])
    }

    @Test fun statsOverlayWithoutRtx() {
        val text = vlcStatsOsd(RtxVideoStatus(), 1920, 1080, 1884, 1060, picturesLost = 0)
        assertTrue(text.startsWith("VLC | standard | SDR"))
        assertTrue(text.contains("downscale"))
        assertFalse(text.contains('—'))
    }
}
