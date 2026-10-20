package com.puretv.twitch.desktop.data

import com.puretv.twitch.core.notify.LiveNotifyState
import com.puretv.twitch.core.notify.SeenStream
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveNotifyFileStoreTest {
    private val tmp: File = Files.createTempDirectory("live-notify-store").toFile()

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    @Test fun stateSurvivesARestart() = runBlocking {
        val state = LiveNotifyState(
            userId = "me",
            seeded = true,
            seen = mapOf("alice" to SeenStream("42", 1_000)),
            lastNotifiedAtMs = mapOf("alice" to 900),
        )
        val first = LiveNotifyFileStore(tmp)
        first.save(state)
        first.flush()

        assertEquals(state, LiveNotifyFileStore(tmp).load())
    }

    @Test fun noFileMeansAFreshSilentStart() = runBlocking {
        assertEquals(LiveNotifyState(), LiveNotifyFileStore(tmp).load())
    }

    @Test fun aSavedEmptyStateIsNotMistakenForACorruptFile() = runBlocking {
        val store = LiveNotifyFileStore(tmp)
        store.save(LiveNotifyState())
        store.flush()
        assertEquals(LiveNotifyState(), LiveNotifyFileStore(tmp).load())
        assertTrue(tmp.listFiles().orEmpty().none { it.name.contains(".corrupt-") })
    }

    @Test fun aCorruptFileIsSetAsideAndStartsOver() = runBlocking {
        File(tmp, "live-notify.json").writeText("{half a file")

        assertEquals(LiveNotifyState(), LiveNotifyFileStore(tmp).load())
        assertTrue(tmp.listFiles().orEmpty().any { it.name.startsWith("live-notify.json.corrupt-") })
    }
}
