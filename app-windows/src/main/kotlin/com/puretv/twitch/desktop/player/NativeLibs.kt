package com.puretv.twitch.desktop.player

import com.puretv.twitch.desktop.platform.HostOs
import java.io.File

/**
 * Where the playback engines' native libraries live on each OS, and what they are called.
 *
 * - Windows: the installer bundles VLC (`vlc/libvlc.dll` + `vlc/plugins`) and libmpv
 *   (`mpv/libmpv-2.dll`) in the app's resources folder.
 * - macOS: the app bundles VLC as `vlc/lib/libvlc.dylib` with `vlc/plugins` beside
 *   `lib`, the layout of VLC.app that vlcj's macOS discovery expects (it points
 *   `VLC_PLUGIN_PATH` at `lib/../plugins`). mpv is not offered: it can only draw into a
 *   native window handle, and a Java window on macOS has none it can use.
 * - Linux: the distro's libVLC and libmpv, which the `.deb` depends on. Nothing is bundled.
 */
object NativeLibs {

    /** False on macOS, where libmpv has no window to draw into (see above). */
    fun mpvSupported(os: HostOs = HostOs.current): Boolean = os != HostOs.MACOS

    /** The folder holding a bundled libVLC inside [resources], or null when there is none. */
    fun bundledVlcLibDir(resources: File, os: HostOs = HostOs.current): File? = when (os) {
        HostOs.WINDOWS -> File(resources, "vlc").takeIf { File(it, "libvlc.dll").isFile }
        HostOs.MACOS -> File(resources, "vlc/lib").takeIf { File(it, "libvlc.dylib").isFile }
        HostOs.LINUX -> null
    }

    /** The libmpv file a bundled copy (or `MPV_DIR` in development) must contain. */
    fun mpvFileName(os: HostOs = HostOs.current): String = when (os) {
        HostOs.WINDOWS -> "libmpv-2.dll"
        HostOs.MACOS -> "libmpv.2.dylib"
        HostOs.LINUX -> "libmpv.so.2"
    }

    /**
     * Names to hand JNA, tried in order. On Linux, plain "mpv" needs the `-dev` package's
     * unversioned symlink, so the versioned soname the runtime package ships comes next.
     */
    fun mpvLoadNames(os: HostOs = HostOs.current): List<String> = when (os) {
        HostOs.WINDOWS -> listOf("libmpv-2")
        HostOs.MACOS -> listOf("mpv.2", "mpv")
        HostOs.LINUX -> listOf("mpv", "libmpv.so.2")
    }

    /**
     * The libVLC audio output for multi-stream tiles, or null for VLC's default. Each tile
     * needs its own volume. On Windows the default output (mmdevice) shares one volume per
     * process, measured with two live players, so tiles use DirectSound. On Linux PulseAudio
     * (also what PipeWire serves) gives each player its own stream. macOS keeps VLC's default
     * (auhal); whether its volume is per player there has not been measured.
     */
    fun tileAudioOutput(os: HostOs = HostOs.current): String? = when (os) {
        HostOs.WINDOWS -> "directsound"
        HostOs.LINUX -> "pulse"
        HostOs.MACOS -> null
    }

    /** What to tell the viewer when libVLC cannot be loaded. */
    fun vlcMissingMessage(packaged: Boolean, os: HostOs = HostOs.current): String = when {
        os == HostOs.LINUX ->
            "VLC was not found. Install it with your package manager (for example: sudo apt install vlc) and restart PureTV."
        packaged -> "VLC bundle missing. Please reinstall PureTV for Twitch."
        else -> "VLC not found. Install VLC from https://videolan.org/vlc/ and restart PureTV."
    }
}
