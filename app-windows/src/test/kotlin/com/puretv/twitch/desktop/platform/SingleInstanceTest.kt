package com.puretv.twitch.desktop.platform

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    fun aCopyHiddenInTheTrayIsAskedToShowItself() {
        // Hidden in the tray there is no visible window to focus, so the second launch
        // asks the running copy to show itself and leaves once it has answered.
        val primary = SingleInstance(lockFile, focusExistingWindow = { false })
        assertTrue(primary.acquire())
        val activations = CountDownLatch(1)
        primary.listenForActivation { activations.countDown() }
        try {
            val second = SingleInstance(lockFile, focusExistingWindow = { false })
            assertFalse(second.acquire(waitForClosingCopyMs = 5_000))
            assertTrue(activations.await(5, TimeUnit.SECONDS), "the running copy was asked to show")
        } finally {
            primary.stopListening()
        }
    }

    @Test
    fun aRequestNobodyAnsweredIsClearedByTheNextCopy() {
        // A launch that gave up waiting on a copy that was closing leaves its request
        // behind. The copy that runs next must not treat it as a fresh "show yourself".
        val stale = File(File(dir, "activate").apply { mkdirs() }, "show.request").apply { writeText("show") }
        val primary = SingleInstance(lockFile, focusExistingWindow = { false })
        assertTrue(primary.acquire())
        assertFalse(stale.exists())
        var activated = 0
        primary.listenForActivation { activated++ }
        primary.stopListening()
        assertEquals(0, activated)
    }

    @Test
    fun aCopyThatStoppedListeningLeavesALaterRequestForTheNextCopy() {
        // Quit, then an immediate relaunch: the dying copy must not delete the request.
        val dying = SingleInstance(lockFile, focusExistingWindow = { false })
        assertTrue(dying.acquire())
        var activated = 0
        dying.listenForActivation { activated++ }
        dying.stopListening()

        val relaunch = SingleInstance(lockFile, focusExistingWindow = { false })
        assertTrue(relaunch.acquire(waitForClosingCopyMs = 1_000), "nobody answered, so the relaunch runs itself")
        assertEquals(0, activated)
    }

    @Test
    fun anUnusableLockFileNeverStopsTheApp() {
        val notAFile = File(dir, "a-directory").apply { mkdirs() }
        assertTrue(SingleInstance(notAFile, focusExistingWindow = { true }).acquire())
    }
}
