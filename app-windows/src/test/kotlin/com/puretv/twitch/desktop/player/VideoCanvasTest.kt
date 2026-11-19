package com.puretv.twitch.desktop.player

import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoCanvasTest {
    @Test
    fun `a wide frame in a tall box is letterboxed and centred`() {
        assertEquals(VideoCanvas.Box(0, 75, 400, 225), VideoCanvas.fit(1920, 1080, 400, 375))
    }

    @Test
    fun `a frame in a wider box is pillarboxed`() {
        assertEquals(VideoCanvas.Box(100, 0, 400, 225), VideoCanvas.fit(1920, 1080, 600, 225))
    }

    @Test
    fun `the surface is never zero-sized, so VLC never divides by zero`() {
        val canvas = VideoCanvas()
        canvas.setSize(0, 0)
        assertEquals(VideoCanvas.MIN_SIDE, canvas.width)
        assertEquals(VideoCanvas.MIN_SIDE, canvas.height)
        canvas.setBounds(10, 20, 1280, 0)
        assertEquals(1280, canvas.width)
        assertEquals(VideoCanvas.MIN_SIDE, canvas.height)
        canvas.setBounds(0, 0, 1920, 1080)
        assertEquals(1920, canvas.width)
        assertEquals(1080, canvas.height)
    }

    @Test
    fun `nothing to fit gives an empty box`() {
        assertEquals(VideoCanvas.Box(0, 0, 0, 0), VideoCanvas.fit(0, 1080, 600, 225))
        assertEquals(VideoCanvas.Box(0, 0, 0, 0), VideoCanvas.fit(1920, 1080, 0, 0))
    }

    @Test
    fun `RV32 bytes become the right RGB pixels`() {
        // libVLC's RV32 is B, G, R, X per pixel in memory; native buffers are little-endian on
        // every machine PureTV ships for.
        val px = ByteBuffer.allocateDirect(2 * 4).order(ByteOrder.nativeOrder())
        px.put(byteArrayOf(0x33, 0x22, 0x11, 0xFF.toByte(), 0x00, 0x00, 0xFF.toByte(), 0x00)).flip()
        val canvas = CapturingCanvas()
        canvas.present(px, 2, 1)
        val image = canvas.snapshot()
        assertEquals(0x112233, image.getRGB(0, 0) and 0xFFFFFF)
        assertEquals(0xFF0000, image.getRGB(1, 0) and 0xFFFFFF)
    }

    /** Paints into an image instead of a screen. */
    private class CapturingCanvas : VideoCanvas() {
        fun snapshot(): BufferedImage {
            setSize(2, 1)
            val out = BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            paint(g)
            g.dispose()
            return out
        }
    }
}
