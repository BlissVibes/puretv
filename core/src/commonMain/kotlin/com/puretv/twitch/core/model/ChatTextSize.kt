package com.puretv.twitch.core.model

import kotlin.math.abs

/**
 * Chat text size presets. Stored in [AppSettings.chatFontSize] as the base text
 * size in sp, so older settings files (always 14) read as [DEFAULT]. Apps apply
 * [scale] to the whole chat column, so emotes and badges grow with the text.
 */
enum class ChatTextSize(val label: String, val sp: Float) {
    SMALL("Small", 12f),
    DEFAULT("Default", 14f),
    LARGE("Large", 16f),
    LARGER("Larger", 19f),
    HUGE("Huge", 22f),
    ;

    val scale: Float get() = sp / DEFAULT.sp

    companion object {
        /** The preset closest to a stored size. */
        fun from(sp: Float): ChatTextSize = entries.minBy { abs(it.sp - sp) }
    }
}
