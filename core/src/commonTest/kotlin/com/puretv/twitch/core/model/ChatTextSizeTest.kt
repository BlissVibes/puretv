package com.puretv.twitch.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatTextSizeTest {
    @Test
    fun storedSizesMapToTheNearestPreset() {
        assertEquals(ChatTextSize.DEFAULT, ChatTextSize.from(AppSettings().chatFontSize))
        assertEquals(ChatTextSize.LARGE, ChatTextSize.from(16f))
        assertEquals(ChatTextSize.HUGE, ChatTextSize.from(40f))
        assertEquals(1f, ChatTextSize.DEFAULT.scale)
    }
}
