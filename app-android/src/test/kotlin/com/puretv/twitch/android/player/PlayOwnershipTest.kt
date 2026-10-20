package com.puretv.twitch.android.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayOwnershipTest {
    private val a = Any()
    private val b = Any()

    @Test
    fun theOwnerCanReleaseOnce() {
        val ownership = PlayOwnership<Any>()
        assertTrue(ownership.claim(a))
        assertFalse(ownership.claim(a), "same owner again is not a change")
        assertTrue(ownership.release(a))
        assertFalse(ownership.release(a))
    }

    @Test
    fun aStaleOwnerCleaningUpAfterTheOtherTookOverChangesNothing() {
        // Watching A, an alert opens B on top, Back: A plays again and claims the
        // player, and only then does B's view model clear.
        val ownership = PlayOwnership<Any>()
        ownership.claim(a)
        ownership.claim(b)
        assertTrue(ownership.claim(a))
        assertFalse(ownership.release(b), "B no longer owns the player")
        assertTrue(ownership.release(a))
    }

    @Test
    fun aViewModelThatNeverPlayedCannotStopAnything() {
        val ownership = PlayOwnership<Any>()
        ownership.claim(a)
        assertFalse(ownership.release(b))
        assertTrue(ownership.release(a))
    }
}
