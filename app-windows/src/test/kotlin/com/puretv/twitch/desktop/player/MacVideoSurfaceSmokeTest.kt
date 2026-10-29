package com.puretv.twitch.desktop.player

import com.puretv.twitch.desktop.platform.Platform
import java.awt.Dimension
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in, on-a-Mac-with-a-display smoke test of the macOS video path: libVLC
 * (bundled or `VLC_APP`) decodes a public HLS test stream into a
 * [MacVideoSurfacePanel] and, within a few seconds, that panel is painting
 * frames that are not plain black. This is the check a screenshot would be.
 *
 * Runs only with `PURETV_VLC_SMOKE=1` (it needs the network, VLC and a window),
 * so the normal suite stays hermetic:
 *
 *   PURETV_VLC_SMOKE=1 VLC_APP=/path/to/VLC.app ./gradlew :app-windows:test --tests '*MacVideoSurfaceSmokeTest*'
 */
class MacVideoSurfaceSmokeTest {

    @Test
    fun framesReachThePanel() {
        if (System.getenv("PURETV_VLC_SMOKE") != "1" || !Platform.isMac) return

        val engine = VlcEngine()
        engine.warmUp()
        assertTrue(engine.isAvailable, "libVLC did not load (set VLC_APP to an arm64 VLC.app)")

        val player = VlcPlayer(engine)
        lateinit var panel: MacVideoSurfacePanel
        lateinit var frame: JFrame
        SwingUtilities.invokeAndWait {
            panel = MacVideoSurfacePanel()
            frame = JFrame("PureTV smoke").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                contentPane.add(panel)
                preferredSize = Dimension(640, 360)
                pack()
                isVisible = true
            }
            player.attachToPanel(panel)
        }
        player.play(TEST_STREAM)

        var lit = false
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && !lit) {
            Thread.sleep(500)
            SwingUtilities.invokeAndWait {
                val img = BufferedImage(panel.width.coerceAtLeast(1), panel.height.coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                panel.paint(g)
                g.dispose()
                lit = nonBlackFraction(img) > 0.05
            }
        }
        val status = player.status.value
        SwingUtilities.invokeAndWait {
            player.stop()
            frame.dispose()
        }
        player.release()
        engine.releaseIfBuilt()
        assertTrue(lit, "no video frame was painted within 30s; player status: $status")
    }

    private fun nonBlackFraction(img: BufferedImage): Double {
        var lit = 0
        var total = 0
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                val rgb = img.getRGB(x, y) and 0xFFFFFF
                if (rgb > 0x101010) lit++
                total++
                x += 8
            }
            y += 8
        }
        return if (total == 0) 0.0 else lit.toDouble() / total
    }

    private companion object {
        /** Public HLS test stream (Mux's Big Buck Bunny), no account involved. */
        const val TEST_STREAM = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    }
}
