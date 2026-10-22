package com.puretv.twitch.desktop.player

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurface
import java.awt.Component
import javax.swing.SwingUtilities

/**
 * The audio output multi-stream tiles use (per OS, see [NativeLibs.tileAudioOutput]).
 * VLC's default Windows output (mmdevice)
 * sets volume and mute on the process's one audio session, so with several players
 * muting the quiet tiles silenced the active one too: measured with two live
 * players, setting one player's volume changed the other's. DirectSound keeps a
 * volume per player (measured the same way), which is what "only the active tile is
 * audible" needs. The single-stream player keeps VLC's default.
 */
val TILE_AUDIO_OUTPUT: String? = NativeLibs.tileAudioOutput()

/**
 * The one libVLC instance of the app. Loading libVLC scans its plugins, which is
 * slow, so it happens once and every [VlcPlayer] (the single-stream player, and
 * one per multi-stream tile) makes its own media player from it.
 *
 * VLC resolution order:
 *   1. Bundled copy: when running as a packaged distributable, looks for
 *      `vlc/libvlc.dll` inside `compose.application.resources.dir` (populated
 *      by `gradle :app-windows:bundleVlc` before packaging).
 *   2. System installation: falls back to auto-discovery (standard VLC
 *      install paths, jna.library.path, PATH) for development builds.
 *
 * Construction is cheap; libVLC loads on first use (the background warm-up, the
 * first stream open, or the first multi-stream tile), never on the way to the
 * first window. [extraArgs] is read at that moment, so the audio normalizer
 * setting is whatever it is when VLC starts (VLC 3.0.23 only loads audio filters
 * per instance, so a change applies after a restart).
 */
class VlcEngine(private val extraArgs: () -> List<String> = { emptyList() }) {

    private val lazyFactory = lazy {
        // jna.library.path must be set before VLCJ's JNA binding loads libvlc.dll.
        detectBundledVlc()
        val extra = extraArgs()
        buildFactory(extra)
            ?: if (extra.isEmpty()) null else {
                // An extra argument libVLC refuses makes it refuse to start at all;
                // playing without the extras beats not playing.
                System.err.println("VLC rejected the extra arguments $extra, starting without them")
                buildFactory(emptyList())
            }
    }

    private val factory: MediaPlayerFactory? get() = lazyFactory.value

    val isAvailable: Boolean get() = factory != null

    /** Builds libVLC ahead of the first stream. Call off the UI thread. */
    fun warmUp() {
        factory
    }

    /** A new media player on the shared instance, or null when VLC is not installed. */
    fun newEmbeddedPlayer(): EmbeddedMediaPlayer? = factory?.mediaPlayers()?.newEmbeddedMediaPlayer()

    /**
     * A video surface for [component]: its native window on Windows and Linux, or on
     * macOS frames decoded into memory that the [VideoCanvas] paints itself.
     */
    fun newVideoSurface(component: Component): VideoSurface? {
        val surfaces = factory?.videoSurfaces() ?: return null
        if (NativeLibs.callbackVideo() && component is VideoCanvas) {
            val sink = FrameSink(component)
            return surfaces.newVideoSurface(sink, sink, true)
        }
        return surfaces.newVideoSurface(component)
    }

    /** Frees libVLC. Call once at shutdown, after every player made from it is released. */
    fun releaseIfBuilt() {
        if (!lazyFactory.isInitialized()) return
        val f = lazyFactory.value ?: return
        SwingUtilities.invokeLater { runCatching { f.release() } }
    }

    private companion object {
        // `--no-video-title-show`   suppress VLC's on-video filename overlay.
        // `--network-caching=2000`  2s network buffer; smooths over Twitch's variable
        //                           segment delivery without adding noticeable latency.
        // `--live-caching=1000`     separate, shorter buffer specifically for live sources.
        // `--sout-mux-caching=500`  output muxer cache; keeps A/V sync tight.
        fun buildFactory(extra: List<String>): MediaPlayerFactory? = runCatching {
            MediaPlayerFactory(
                "--no-video-title-show",
                "--network-caching=2000",
                "--live-caching=1000",
                "--sout-mux-caching=500",
                *extra.toTypedArray(),
            )
        }.getOrNull()

        // Checks for a VLC copy bundled inside the distributable's resources dir.
        // When found, prepends the directory to jna.library.path so JNA locates
        // libvlc.dll before scanning system paths. VLC then finds its plugins/
        // subdirectory relative to its own DLL location automatically.
        fun detectBundledVlc(): String? {
            val resourcesDir = System.getProperty("compose.application.resources.dir")
                ?: return null
            // Windows: vlc/libvlc.dll. macOS: vlc/lib/libvlc.dylib. Linux uses the distro's libVLC.
            val vlcDir = NativeLibs.bundledVlcLibDir(java.io.File(resourcesDir)) ?: return null
            val existing = System.getProperty("jna.library.path")
            System.setProperty(
                "jna.library.path",
                if (existing.isNullOrBlank()) vlcDir.absolutePath
                else "${vlcDir.absolutePath}${java.io.File.pathSeparator}$existing",
            )
            // The engine can be built after libmpv already went through JNA (multi-stream
            // with the mpv engine selected), so name the folder to JNA directly as well.
            runCatching {
                com.sun.jna.NativeLibrary.addSearchPath("libvlc", vlcDir.absolutePath)
                com.sun.jna.NativeLibrary.addSearchPath("libvlccore", vlcDir.absolutePath)
            }
            return vlcDir.absolutePath
        }
    }
}
