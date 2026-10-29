package com.puretv.twitch.desktop.player

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import javax.swing.JComponent

/**
 * macOS video surface.
 *
 * On Windows libVLC paints straight into a heavyweight AWT `Canvas` through its
 * native window handle (see `VlcPlayerView`). macOS has no such handle to give:
 * Java dropped heavyweight embedding there, so the only way to show libVLC's
 * video inside a JVM window is libVLC's "callback" output, where VLC decodes
 * each frame into a memory buffer we own and we draw it ourselves.
 *
 * This component is that drawing: a lightweight Swing component holding one
 * [BufferedImage] whose pixel array IS the buffer VLC writes into (RV32 = BGRA
 * in memory, read as packed int RGB by Java2D, exactly what vlcj's own callback
 * player component does). Every frame VLC finishes, [onDisplay] asks for a
 * repaint; [paintComponent] letterboxes the image into the component. Nothing
 * else changes: the player, the proxy and every control stay as they are.
 *
 * Cost: one 1080p frame is ~8 MB; at 60 fps that is a copy VLC does into our
 * array plus one scaled blit per frame, which an Apple Silicon Mac does without
 * noticing. Hardware (VideoToolbox) decoding still applies; only the final
 * frame crosses back to the CPU.
 */
class MacVideoSurfacePanel(private val onRemoveNotify: () -> Unit = {}) : JComponent() {

    @Volatile private var image: BufferedImage? = null

    init {
        background = Color.BLACK
        isOpaque = true
        isFocusable = false
    }

    /** Called by the player when its surface is detached: the last frame is dropped. */
    fun clear() {
        image = null
        repaint()
    }

    override fun removeNotify() {
        onRemoveNotify()
        super.removeNotify()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.color = Color.BLACK
        g2.fillRect(0, 0, width, height)
        val img = image ?: return
        val iw = img.width
        val ih = img.height
        if (iw <= 0 || ih <= 0 || width <= 0 || height <= 0) return
        // Fit inside, keep aspect ratio, centre: the same letterboxing libVLC does itself.
        val scale = minOf(width.toDouble() / iw, height.toDouble() / ih)
        val dw = (iw * scale).toInt()
        val dh = (ih * scale).toInt()
        val dx = (width - dw) / 2
        val dy = (height - dh) / 2
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
        g2.drawImage(img, dx, dy, dw, dh, null)
    }

    /**
     * Builds the libVLC callback surface that renders into this component. One
     * per media player; VLC asks [BufferFormatCallbackAdapter.getBufferFormat] for a
     * buffer whenever the stream's size changes (quality switch, new channel), and
     * a fresh image of that size takes over.
     */
    fun newVideoSurface(factory: MediaPlayerFactory): VideoSurface {
        val render = object : RenderCallbackAdapter() {
            override fun onDisplay(mediaPlayer: MediaPlayer, rgbBuffer: IntArray) {
                repaint()
            }
        }
        val format = object : BufferFormatCallbackAdapter() {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                val img = BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_RGB)
                render.setBuffer((img.raster.dataBuffer as DataBufferInt).data)
                image = img
                return RV32BufferFormat(sourceWidth, sourceHeight)
            }

            override fun allocatedBuffers(buffers: Array<ByteBuffer>) {
                // Nothing to do: the format callback above already installed the target array.
            }
        }
        // lockBuffers = true: VLC's native buffer stays pinned while our copy runs.
        return factory.videoSurfaces().newVideoSurface(format, render, true)
            ?: CallbackVideoSurface(format, render, true, VideoSurfaceAdapters.getVideoSurfaceAdapter())
    }
}
