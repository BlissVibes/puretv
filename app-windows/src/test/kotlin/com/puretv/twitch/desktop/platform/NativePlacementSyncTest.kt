package com.puretv.twitch.desktop.platform

import androidx.compose.ui.window.WindowPlacement.Floating
import androidx.compose.ui.window.WindowPlacement.Fullscreen
import com.puretv.twitch.desktop.platform.NativePlacementSync.SETTLE_MS
import com.puretv.twitch.desktop.platform.NativePlacementSync.Step
import com.puretv.twitch.desktop.platform.NativePlacementSync.step
import kotlin.test.Test
import kotlin.test.assertEquals

class NativePlacementSyncTest {

    @Test fun asksAtOnceWhenNothingIsRunning() {
        assertEquals(Step.ISSUE, step(target = Fullscreen, actual = Floating, sinceIssueMs = null, attempts = 0))
    }

    @Test fun nothingToDoWhenAlreadyThere() {
        assertEquals(Step.DONE, step(target = Floating, actual = Floating, sinceIssueMs = null, attempts = 0))
    }

    // The bug: leaving 0.4 s after entering. The window already reads Fullscreen but macOS is
    // still animating, so the exit must wait rather than be asked (and ignored) now.
    @Test fun leavingDuringTheEnterAnimationWaits() {
        assertEquals(Step.WAIT, step(target = Floating, actual = Fullscreen, sinceIssueMs = 400, attempts = 1))
    }

    @Test fun leavingIsAskedOnceTheAnimationIsOver() {
        assertEquals(Step.ISSUE, step(target = Floating, actual = Fullscreen, sinceIssueMs = SETTLE_MS, attempts = 1))
    }

    // Leaving takes ~1.35 s to show; reading Fullscreen 1.3 s in is not a failure yet.
    @Test fun aLeaveStillAnimatingIsNotAskedAgain() {
        assertEquals(Step.WAIT, step(target = Floating, actual = Fullscreen, sinceIssueMs = 1300, attempts = 1))
    }

    @Test fun doneOnceSettledWhereWanted() {
        assertEquals(Step.DONE, step(target = Fullscreen, actual = Fullscreen, sinceIssueMs = SETTLE_MS, attempts = 1))
    }

    @Test fun givesUpAfterTheLastAttempt() {
        assertEquals(Step.GIVE_UP, step(target = Floating, actual = Fullscreen, sinceIssueMs = SETTLE_MS, attempts = NativePlacementSync.MAX_ATTEMPTS))
    }
}
