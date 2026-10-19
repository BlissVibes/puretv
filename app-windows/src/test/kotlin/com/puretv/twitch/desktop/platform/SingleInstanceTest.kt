package com.puretv.twitch.desktop.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceTest {
    private val dir: File = Files.createTempDirectory("puretv-instance").toFile()
    private val lockFile = File(dir, "instance.lock")

    @Test
    fun theFirstCopyRuns() {
        assertTrue(SingleInstance(lockFile, focusExistingWindow = { false }).acquire())
    }

    @Test
    fun aSecondCopyHandsOverToTheVisibleWindowAndExits() {
        SingleInstance(lockFile, focusExistingWindow = { false }).acquire()
        var focused = 0
        val second = SingleInstance(lockFile, focusExistingWindow = { focused++; true })

        assertFalse(second.acquire())
        assertEquals(1, focused)
    }

    @Test
    fun aCopyThatIsStillClosingIsWaitedForNotFocused() {
        SingleInstance(lockFile, focusExistingWindow = { false }).acquire()
        var now = 0L
        var waits = 0
        val relaunch = SingleInstance(
            lockFile,
            focusExistingWindow = { false },
            sleep = { now += it; waits++ },
            nowMs = { now },
        )

        // Nothing to hand over to and the lock never frees: run anyway after the wait.
        assertTrue(relaunch.acquire(waitForClosingCopyMs = 1_000))
        assertTrue(waits in 4..5, "polled every 250ms for a second, got $waits")
    }

    @Test
    fun anUnusableLockFileNeverStopsTheApp() {
        val notAFile = File(dir, "a-directory").apply { mkdirs() }
        assertTrue(SingleInstance(notAFile, focusExistingWindow = { true }).acquire())
    }
}
