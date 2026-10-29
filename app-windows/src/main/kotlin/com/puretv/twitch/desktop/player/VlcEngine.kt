package com.puretv.twitch.desktop.player

import com.puretv.twitch.desktop.platform.Platform
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurface
import java.awt.Component
import java.io.File
import javax.swing.SwingUtilities

/**
 * The audio output multi-stream tiles use. VLC's default Windows output (mmdevice)
 * sets volume and mute on the process's one audio session, so with several players
 * muting the quiet tiles silenced the active one too: measured with two live
 * players, setting one player's volume changed the other's. DirectSound keeps a
 * volume per player (measured the same way), which is what "only the active tile is
 * audible" needs. The single-stream player keeps VLC's default.
 */
val TILE_AUDIO_OUTPUT: String? = if (Platform.isMac) null else "directsound"

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
        val bundled = detectBundledVlc()
        val extra = extraArgs()
        buildFactory(extra, bundled)
            ?: if (extra.isEmpty()) null else {
                // An extra argument libVLC refuses makes it refuse to start at all;
                // playing without the extras beats not playing.
                System.err.println("VLC rejected the extra arguments $extra, starting without them")
                buildFactory(emptyList(), bundled)
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
     * A video surface for [component]: on Windows bound to its native window; on
     * macOS, where a [MacVideoSurfacePanel] is passed, the callback surface that
     * paints into it (see that class for why).
     */
    fun newVideoSurface(component: Component): VideoSurface? {
        val f = factory ?: return null
        return if (component is MacVideoSurfacePanel) component.newVideoSurface(f)
        else f.videoSurfaces().newVideoSurface(component)
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
        fun buildFactory(extra: List<String>, bundled: String?): MediaPlayerFactory? = runCatching {
            val args = arrayOf(
                "--no-video-title-show",
                "--network-caching=2000",
                "--live-caching=1000",
                "--sout-mux-caching=500",
                *extra.toTypedArray(),
            )
            // With a bundled copy already named to JNA (and its plugin path exported),
            // vlcj's own discovery must stay out of it: on a Mac it would find a VLC.app
            // in /Applications and point VLC_PLUGIN_PATH at THAT copy's plugins, which
            // for an Intel VLC.app leaves the arm64 libvlccore with no usable plugins.
            if (bundled != null) MediaPlayerFactory(NativeDiscovery(), *args) else MediaPlayerFactory(*args)
        }.getOrNull()

        // Checks for a VLC copy bundled inside the distributable's resources dir.
        // When found, prepends the directory to jna.library.path so JNA locates
        // libvlc before scanning system paths.
        //  - Windows: `vlc/libvlc.dll`; VLC then finds its plugins/ next to the DLL.
        //  - macOS:   `vlc/lib/libvlc.dylib` + `vlc/plugins/` (the layout of
        //             VLC.app/Contents/MacOS, copied by `gradle :app-windows:bundleVlcMac`).
        //             libvlccore needs VLC_PLUGIN_PATH to find plugins outside a .app,
        //             so it is exported the way vlcj's own discovery would.
        // On a Mac with no bundle, VLC_APP (a VLC.app path) serves development runs.
        fun detectBundledVlc(): String? {
            val resourcesDir = System.getProperty("compose.application.resources.dir")
            if (Platform.isMac) {
                val candidates = listOfNotNull(
                    resourcesDir?.let { File(it, "vlc") },
                    System.getenv("VLC_APP")?.let { File(it, "Contents/MacOS") },
                )
                val vlcDir = candidates.firstOrNull { File(it, "lib/libvlc.dylib").exists() } ?: return null
                val libDir = File(vlcDir, "lib")
                val pluginsDir = File(vlcDir, "plugins")
                prependLibraryPath(libDir)
                runCatching {
                    com.sun.jna.NativeLibrary.addSearchPath("vlc", libDir.absolutePath)
                    com.sun.jna.NativeLibrary.addSearchPath("vlccore", libDir.absolutePath)
                }
                runCatching {
                    uk.co.caprica.vlcj.binding.lib.LibC.INSTANCE.setenv("VLC_PLUGIN_PATH", pluginsDir.absolutePath, 1)
                }
                return vlcDir.absolutePath
            }
            val vlcDir = File(resourcesDir ?: return null, "vlc")
            if (!vlcDir.exists() || !File(vlcDir, "libvlc.dll").exists()) return null
            prependLibraryPath(vlcDir)
            // The engine can be built after libmpv already went through JNA (multi-stream
            // with the mpv engine selected), so name the folder to JNA directly as well.
            runCatching {
                com.sun.jna.NativeLibrary.addSearchPath("libvlc", vlcDir.absolutePath)
                com.sun.jna.NativeLibrary.addSearchPath("libvlccore", vlcDir.absolutePath)
            }
            return vlcDir.absolutePath
        }

        private fun prependLibraryPath(dir: File) {
            val existing = System.getProperty("jna.library.path")
            System.setProperty(
                "jna.library.path",
                if (existing.isNullOrBlank()) dir.absolutePath
                else "${dir.absolutePath}${File.pathSeparator}$existing",
            )
        }
    }
}
