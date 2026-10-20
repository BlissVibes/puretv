package com.puretv.twitch.desktop.data

import com.puretv.twitch.core.di.TokenHolder
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Show 7TV name paints" is on by default, survives a restart, and old settings files keep it on. */
class NamePaintSettingTest {
    private val tmp: File = Files.createTempDirectory("name-paint-setting").toFile()

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    @Test fun onByDefault() {
        assertTrue(DesktopSettingsStore(TokenHolder(), tmp).settings.value.showSevenTvPaints)
    }

    @Test fun turningItOffIsKept() {
        val store = DesktopSettingsStore(TokenHolder(), tmp)
        store.updateSettings { it.copy(showSevenTvPaints = false) }
        store.flush()
        assertFalse(DesktopSettingsStore(TokenHolder(), tmp).settings.value.showSevenTvPaints)
    }

    @Test fun settingsFileFromBeforeTheSettingKeepsItOn() {
        File(tmp, "settings.json").writeText("""{"preferredQuality":"auto","chatTimestamps":true}""")
        val settings = DesktopSettingsStore(TokenHolder(), tmp).settings.value
        assertTrue(settings.chatTimestamps)
        assertTrue(settings.showSevenTvPaints)
    }
}
