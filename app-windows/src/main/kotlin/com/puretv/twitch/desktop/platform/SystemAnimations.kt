package com.puretv.twitch.desktop.platform

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference

/**
 * Windows' "Animation effects" switch (Settings, Accessibility, Visual effects). Read
 * through `SystemParametersInfo(SPI_GETCLIENTAREAANIMATION)` so PureTV honours the same
 * preference as every other app instead of growing its own switch.
 */
object SystemAnimations {
    private const val SPI_GETCLIENTAREAANIMATION = 0x1042

    private interface User32Spi : Library {
        fun SystemParametersInfoW(uiAction: Int, uiParam: Int, pvParam: IntByReference, fWinIni: Int): Boolean
    }

    private val user32: User32Spi? by lazy {
        runCatching { Native.load("user32", User32Spi::class.java) }.getOrNull()
    }

    /** True when animation effects are on, and also when the answer cannot be read. */
    fun enabled(): Boolean {
        val api = user32 ?: return true
        val out = IntByReference(1)
        return runCatching { !api.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, out, 0) || out.value != 0 }
            .getOrDefault(true)
    }
}
