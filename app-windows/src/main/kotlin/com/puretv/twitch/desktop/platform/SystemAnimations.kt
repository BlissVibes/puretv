package com.puretv.twitch.desktop.platform

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The system's "reduce motion" preference, so PureTV honours the same switch as every
 * other app instead of growing its own:
 *
 * - Windows: "Animation effects" (Settings, Accessibility, Visual effects), read through
 *   `SystemParametersInfo(SPI_GETCLIENTAREAANIMATION)`.
 * - macOS: "Reduce motion" (Accessibility, Display), the `reduceMotion` default.
 * - Linux: GNOME's "Animations" switch (`enable-animations`); other desktops have no
 *   common setting, so they keep motion on.
 *
 * Reading the macOS and Linux settings runs a small command, so that happens in the
 * background and [enabled] answers with the last value read.
 */
object SystemAnimations {
    private const val SPI_GETCLIENTAREAANIMATION = 0x1042

    private interface User32Spi : Library {
        fun SystemParametersInfoW(uiAction: Int, uiParam: Int, pvParam: IntByReference, fWinIni: Int): Boolean
    }

    private val user32: User32Spi? by lazy {
        runCatching { Native.load("user32", User32Spi::class.java) }.getOrNull()
    }

    @Volatile private var lastRead: Boolean = true
    private val reading = AtomicBoolean(false)

    /** True when animation effects are on, and also when the answer cannot be read. */
    fun enabled(): Boolean = when (HostOs.current) {
        HostOs.WINDOWS -> windowsEnabled()
        else -> {
            refreshInBackground()
            lastRead
        }
    }

    private fun windowsEnabled(): Boolean {
        val api = user32 ?: return true
        val out = IntByReference(1)
        return runCatching { !api.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, out, 0) || out.value != 0 }
            .getOrDefault(true)
    }

    private fun refreshInBackground() {
        if (!reading.compareAndSet(false, true)) return
        Thread({
            try {
                lastRead = when (HostOs.current) {
                    HostOs.MACOS -> parseMacReduceMotion(run("defaults", "read", "com.apple.universalaccess", "reduceMotion"))
                    else -> parseGnomeAnimations(run("gsettings", "get", "org.gnome.desktop.interface", "enable-animations"))
                } ?: true
            } finally {
                reading.set(false)
            }
        }, "puretv-motion-pref").apply { isDaemon = true }.start()
    }

    private fun run(vararg command: String): String? = runCatching {
        val p = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroy()
            return@runCatching null
        }
        if (p.exitValue() != 0) null else p.inputStream.bufferedReader().readText()
    }.getOrNull()

    /** `defaults` prints 1 when Reduce motion is on. Null when it could not be read. */
    internal fun parseMacReduceMotion(output: String?): Boolean? = when (output?.trim()) {
        "1" -> false
        "0" -> true
        else -> null
    }

    /** `gsettings` prints true or false. Null when it could not be read. */
    internal fun parseGnomeAnimations(output: String?): Boolean? = when (output?.trim()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
