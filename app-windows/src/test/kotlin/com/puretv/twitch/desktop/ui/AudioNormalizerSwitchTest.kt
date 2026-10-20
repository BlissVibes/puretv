package com.puretv.twitch.desktop.ui

import com.puretv.twitch.core.di.TokenHolder
import com.puretv.twitch.desktop.data.DesktopSettingsStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioNormalizerSwitchTest {
    private val tmp: File = Files.createTempDirectory("normalizer-switch-test").toFile()

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    @Test fun savesTheSettingAndTellsTheRunningPlayer() {
        val store = DesktopSettingsStore(TokenHolder(), tmp)
        val told = mutableListOf<Boolean>()
        val viewModel = SettingsViewModel(store, onAudioNormalizerChange = { told += it })

        viewModel.setAudioNormalizer(true)
        assertTrue(store.settings.value.audioNormalizer)
        viewModel.setAudioNormalizer(false)

        assertEquals(listOf(true, false), told)
        viewModel.dispose()
        store.flush()
    }
}
