package com.puretv.twitch.desktop.platform

import com.sun.jna.NativeLibrary

/**
 * Loads the system's C++ runtime before anything else on Linux.
 *
 * Skia (Compose's renderer, libskiko) carries its own static copy of libstdc++ and exports
 * its symbols. C++ facet ids are process-wide "unique" symbols, so whichever copy loads
 * first owns them. When Skia came first, a distro whose libstdc++ is older than Skia's
 * (Ubuntu 22.04: 12.3) resolved VLC's C++ HLS demuxer to Skia's facets, and the first
 * number it formatted jumped into data: a native crash a second after a stream opened
 * (std::ostream::_M_insert from libadaptive_plugin.so, reproduced under gdb). Loading the
 * system libstdc++ first gives every later library, Skia included, the system's copy.
 *
 * Call it as the first thing in main, before any window exists. No-op elsewhere.
 */
object NativePreload {
    // Held so the library is never unloaded.
    @Suppress("unused")
    private var cxxRuntime: NativeLibrary? = null

    fun linuxCxxRuntime() {
        if (HostOs.current != HostOs.LINUX) return
        cxxRuntime = listOf("libstdc++.so.6", "stdc++").firstNotNullOfOrNull { name ->
            runCatching { NativeLibrary.getInstance(name) }.getOrNull()
        }
    }
}
