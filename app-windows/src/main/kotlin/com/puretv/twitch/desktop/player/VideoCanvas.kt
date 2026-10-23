package com.puretv.twitch.desktop.player

import com.puretv.twitch.core.perf.TimingLog
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.Canvas
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The surface a player draws video on.
 *
 * On Windows and Linux libVLC and libmpv draw straight into this Canvas's native window
 * (an HWND or an X11 window) and nothing here paints. On macOS a Java window offers no
 * native view an engine can draw into, so libVLC decodes each frame into memory instead
 * ([FrameSink]) and this Canvas paints it, scaled to fit with black bars.
 *
 * [onRemove] runs just before the native peer goes away (see VlcPlayerView).
 */
open class VideoCanvas(private val onRemove: () -> Unit = {}) : Canvas() {
    private val lock = Any()
    private var frame: BufferedImage? = null
    @Volatile private var paintedOnce = false

    init {
        background = Color.BLACK
        isFocusable = false
    }

    override fun removeNotify() {
        onRemove()
        super.removeNotify()
    }

    /**
     * Never zero-sized. Layout can hand the surface 0x0 for a moment (going to the mini
     * player), and VLC 3.0.24 with RTX Super Resolution or HDR then divides by that size
     * while fitting the picture (integer divide by zero in libvlccore, the whole app goes).
     * Two pixels look the same as none.
     */
    @Deprecated("AWT's own deprecation; overridden so every size change goes through the clamp")
    override fun reshape(x: Int, y: Int, width: Int, height: Int) {
        @Suppress("DEPRECATION")
        super.reshape(x, y, width.coerceAtLeast(MIN_SIDE), height.coerceAtLeast(MIN_SIDE))
    }

    /** Copies one decoded RV32 frame in and schedules a repaint. Called on libVLC's thread. */
    internal fun present(pixels: ByteBuffer, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        synchronized(lock) {
            val image = frame?.takeIf { it.width == width && it.height == height }
                ?: BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { frame = it }
            val target = (image.raster.dataBuffer as DataBufferInt).data
            // RV32 is B,G,R,X in memory: read in native (little-endian) order it is 0xXXRRGGBB,
            // which TYPE_INT_RGB takes as is. duplicate() resets the order, so set it again.
            val ints = pixels.duplicate().order(ByteOrder.nativeOrder()).apply { rewind() }.asIntBuffer()
            ints.get(target, 0, minOf(target.size, ints.remaining()))
        }
        repaint()
    }

    // Without a frame the default update clears to black. With one, painting over the
    // whole area anyway, so skipping the clear avoids a black flash on every frame.
    override fun update(g: Graphics) {
        if (synchronized(lock) { frame } == null) super.update(g) else paint(g)
    }

    override fun paint(g: Graphics) {
        synchronized(lock) {
            val image = frame ?: return
            if (!paintedOnce) {
                paintedOnce = true
                TimingLog.log("player.frame_painted", 0, "${image.width}x${image.height}")
            }
            val r = fit(image.width, image.height, width, height)
            g.color = Color.BLACK
            if (r.y > 0) {
                g.fillRect(0, 0, width, r.y)
                g.fillRect(0, r.y + r.height, width, height - r.y - r.height)
            }
            if (r.x > 0) {
                g.fillRect(0, 0, r.x, height)
                g.fillRect(r.x + r.width, 0, width - r.x - r.width, height)
            }
            (g as? Graphics2D)?.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(image, r.x, r.y, r.width, r.height, null)
        }
    }

    internal data class Box(val x: Int, val y: Int, val width: Int, val height: Int)

    internal companion object {
        // The smallest the surface may be (see reshape): VLC must never see 0x0.
        const val MIN_SIDE = 2

        /** The largest [srcW]x[srcH] box that fits [dstW]x[dstH], centred (letterbox or pillarbox). */
        fun fit(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Box {
            if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return Box(0, 0, 0, 0)
            val scale = minOf(dstW.toDouble() / srcW, dstH.toDouble() / srcH)
            val w = Math.round(srcW * scale).toInt()
            val h = Math.round(srcH * scale).toInt()
            return Box((dstW - w) / 2, (dstH - h) / 2, w, h)
        }
    }
}

/** libVLC's frame callbacks for [canvas]: RV32 at the source size, handed over as they come. */
internal class FrameSink(private val canvas: VideoCanvas) : BufferFormatCallback, RenderCallback {
    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat = RV32BufferFormat(sourceWidth, sourceHeight)

    override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit

    override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat) {
        nativeBuffers.firstOrNull()?.let { canvas.present(it, bufferFormat.width, bufferFormat.height) }
    }
}
