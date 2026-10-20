package com.puretv.twitch.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteKeysTest {
    private val hidden = RemoteState(controlsVisible = false, chatVisible = false)
    private val shown = hidden.copy(controlsVisible = true)
    private val chat = hidden.copy(chatVisible = true)

    private fun decide(key: RemoteKey, state: RemoteState) = RemoteKeys.decide(key, state)

    @Test fun upAndDownFlipWhileTheControlsAreHidden() {
        assertEquals(RemoteAction.Flip(FlipDirection.NEXT), decide(RemoteKey.Up, hidden))
        assertEquals(RemoteAction.Flip(FlipDirection.PREVIOUS), decide(RemoteKey.Down, hidden))
        assertEquals(RemoteAction.Flip(FlipDirection.NEXT), decide(RemoteKey.Up, chat))
    }

    @Test fun upAndDownMoveFocusWhileTheControlsAreVisible() {
        assertEquals(RemoteAction.PassThrough, decide(RemoteKey.Up, shown))
        assertEquals(RemoteAction.PassThrough, decide(RemoteKey.Down, shown))
        assertEquals(RemoteAction.PassThrough, decide(RemoteKey.Left, shown))
        assertEquals(RemoteAction.PassThrough, decide(RemoteKey.Right, shown))
        assertEquals(RemoteAction.PassThrough, decide(RemoteKey.Center, shown))
    }

    @Test fun channelAndMediaKeysAlwaysFlip() {
        for (state in listOf(hidden, shown, chat)) {
            assertEquals(RemoteAction.Flip(FlipDirection.NEXT), decide(RemoteKey.ChannelUp, state))
            assertEquals(RemoteAction.Flip(FlipDirection.NEXT), decide(RemoteKey.MediaNext, state))
            assertEquals(RemoteAction.Flip(FlipDirection.PREVIOUS), decide(RemoteKey.ChannelDown, state))
            assertEquals(RemoteAction.Flip(FlipDirection.PREVIOUS), decide(RemoteKey.MediaPrevious, state))
        }
    }

    @Test fun okShowsTheControlsUnlessItIsSetToPause() {
        assertEquals(RemoteAction.ShowControls, decide(RemoteKey.Center, hidden))
        assertEquals(RemoteAction.TogglePlayPause, decide(RemoteKey.Center, hidden.copy(okPauses = true)))
        // With the controls up, OK is the focused control's, even when it is set to pause.
        assertEquals(RemoteAction.PassThrough, decide(RemoteKey.Center, shown.copy(okPauses = true)))
    }

    @Test fun okRetriesAFailedStreamEverywhere() {
        for (state in listOf(hidden, shown, chat)) {
            assertEquals(RemoteAction.Retry, decide(RemoteKey.Center, state.copy(hasError = true)))
            assertEquals(RemoteAction.Retry, decide(RemoteKey.PlayPause, state.copy(hasError = true)))
        }
    }

    @Test fun playPauseAndQualityKeysWorkEverywhere() {
        for (state in listOf(hidden, shown, chat)) {
            assertEquals(RemoteAction.TogglePlayPause, decide(RemoteKey.PlayPause, state))
            assertEquals(RemoteAction.StepQuality(up = true), decide(RemoteKey.FastForward, state))
            assertEquals(RemoteAction.StepQuality(up = false), decide(RemoteKey.Rewind, state))
        }
    }

    @Test fun rightOpensChatAndLeftClosesItWhileHidden() {
        assertEquals(RemoteAction.OpenChat, decide(RemoteKey.Right, hidden))
        assertEquals(RemoteAction.Ignore, decide(RemoteKey.Right, chat))
        assertEquals(RemoteAction.CloseChat, decide(RemoteKey.Left, chat))
        assertEquals(RemoteAction.Ignore, decide(RemoteKey.Left, hidden))
    }

    @Test fun backClosesChatThenHidesControlsThenLeaves() {
        assertEquals(RemoteAction.CloseChat, decide(RemoteKey.Back, chat.copy(controlsVisible = true)))
        assertEquals(RemoteAction.HideControls, decide(RemoteKey.Back, shown))
        assertEquals(RemoteAction.Leave, decide(RemoteKey.Back, hidden))
    }

    @Test fun withFlippingOffUpAndDownShowTheControls() {
        val off = hidden.copy(upDownFlips = false)
        assertEquals(RemoteAction.ShowControls, decide(RemoteKey.Up, off))
        assertEquals(RemoteAction.ShowControls, decide(RemoteKey.Down, off))
        // The dedicated channel keys still flip.
        assertEquals(RemoteAction.Flip(FlipDirection.NEXT), decide(RemoteKey.ChannelUp, off))
    }

    @Test fun menuTogglesChatAndOtherKeysPassThrough() {
        assertEquals(RemoteAction.ToggleChat, decide(RemoteKey.Menu, hidden))
        assertEquals(RemoteAction.PassThrough, decide(RemoteKey.Other, hidden))
    }

    @Test fun everyKeyButOtherCountsAsActivity() {
        assertTrue(RemoteKeys.isActivity(RemoteKey.Up))
        assertTrue(RemoteKeys.isActivity(RemoteKey.Back))
        assertFalse(RemoteKeys.isActivity(RemoteKey.Other))
    }
}
