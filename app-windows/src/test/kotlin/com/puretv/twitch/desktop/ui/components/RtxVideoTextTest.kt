package com.puretv.twitch.desktop.ui.components

import com.puretv.twitch.desktop.player.RtxHdrState
import com.puretv.twitch.desktop.player.RtxVideoStatus
import com.puretv.twitch.desktop.player.SuperResolutionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RtxVideoTextTest {
    @Test fun activeSaysFromWhatToWhat() {
        val s = RtxVideoStatus(superResolution = SuperResolutionState.ACTIVE, sourceHeight = 1080, outputHeight = 1440)
        assertEquals("Working: upscaling 1080p to 1440p.", RtxVideoText.superResolution(s))
    }

    @Test fun standbyExplainsWhenItKicksIn() {
        val s = RtxVideoStatus(superResolution = SuperResolutionState.STANDBY, sourceHeight = 1080)
        assertTrue(RtxVideoText.superResolution(s).contains("bigger than it was sent (1080p)"))
    }

    @Test fun wrongGpuPointsAtWindowsGraphicsSettings() {
        val s = RtxVideoStatus(
            superResolution = SuperResolutionState.UNSUPPORTED,
            hdr = RtxHdrState.UNSUPPORTED,
            adapter = "AMD Radeon(TM) Graphics",
            adapterIsNvidia = false,
        )
        assertTrue(RtxVideoText.superResolution(s).startsWith("Windows is running PureTV on AMD Radeon(TM) Graphics."))
        assertTrue(RtxVideoText.hdr(s).contains("High performance"))
    }

    @Test fun driverRefusalOnTheRtxCardSaysUpdate() {
        val s = RtxVideoStatus(superResolution = SuperResolutionState.UNSUPPORTED, adapterIsNvidia = true)
        assertTrue(RtxVideoText.superResolution(s).contains("driver"))
    }

    @Test fun sdrDisplayAsksForWindowsHdr() {
        assertTrue(RtxVideoText.hdr(RtxVideoStatus(hdr = RtxHdrState.DISPLAY_SDR)).contains("Turn on HDR"))
    }

    @Test fun noEmDashes() {
        val all = SuperResolutionState.entries.flatMap { sr ->
            RtxHdrState.entries.map { hdr ->
                val s = RtxVideoStatus(sr, hdr, "GPU", false, 720, 1440)
                RtxVideoText.superResolution(s) + RtxVideoText.hdr(s)
            }
        }
        assertFalse(all.any { it.contains('—') })
    }
}
