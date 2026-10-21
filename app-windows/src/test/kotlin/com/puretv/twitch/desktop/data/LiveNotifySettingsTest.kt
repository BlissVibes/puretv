package com.puretv.twitch.desktop.data

import com.puretv.twitch.core.di.TokenHolder
import com.puretv.twitch.core.model.AppSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Go-live alert and tray settings survive a restart, and older settings files still load. */
class LiveNotifySettingsTest {
    private val tmp: File = Files.createTempDirectory("live-notify-settings").toFile()

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    @Test fun newSettingsRoundTrip() {
        val store = DesktopSettingsStore(TokenHolder(), tmp)
        store.updateSettings {
            it.copy(
                liveNotifications = false,
                notifyMutedLogins = setOf("a", "b"),
                notifyChannels = setOf("Streamer", "other"),
                perChannelNoteDismissed = true,
                closeToTray = true,
                startWithWindows = true,
                trayHintShown = true,
            )
        }
        store.flush()

        val reopened = DesktopSettingsStore(TokenHolder(), tmp).settings.value
        assertEquals(false, reopened.liveNotifications)
        assertEquals(setOf("a", "b"), reopened.notifyMutedLogins)
        assertEquals(setOf("streamer", "other"), reopened.notifyChannels)
        assertEquals(true, reopened.perChannelNoteDismissed)
        assertEquals(true, reopened.closeToTray)
        assertEquals(true, reopened.startWithWindows)
        assertEquals(true, reopened.trayHintShown)
    }

    @Test fun settingsFromBeforeThisVersionLoadWithDefaults() {
        File(tmp, "settings.json").writeText("""{"preferredQuality":"720p","theme":"ember"}""")

        val loaded = DesktopSettingsStore(TokenHolder(), tmp).settings.value
        val defaults = AppSettings()
        assertEquals("720p", loaded.preferredQuality)
        assertEquals(defaults.liveNotifications, loaded.liveNotifications)
        assertEquals(emptySet(), loaded.notifyMutedLogins)
        assertEquals(emptySet(), loaded.notifyChannels, "nobody is opted in until they tap a bell")
        assertEquals(false, loaded.perChannelNoteDismissed)
        assertEquals(false, loaded.closeToTray)
        assertEquals(false, loaded.startWithWindows)
        assertEquals(false, loaded.trayHintShown)
        assertEquals(true, defaults.liveNotifications)
    }
}
