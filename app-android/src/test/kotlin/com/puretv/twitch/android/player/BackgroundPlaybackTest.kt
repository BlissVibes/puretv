package com.puretv.twitch.android.player

import com.puretv.twitch.core.model.ChannelInfo
import com.puretv.twitch.core.model.StreamInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackgroundPlaybackTest {

    @Test
    fun leavingPausesOnlyWithBackgroundPlayOffAndNoPip() {
        assertTrue(BackgroundPlayback.shouldPauseOnLeave(backgroundPlay = false, inPip = false))
        // A visible PiP window keeps playing either way, as it always has.
        assertFalse(BackgroundPlayback.shouldPauseOnLeave(backgroundPlay = false, inPip = true))
        assertFalse(BackgroundPlayback.shouldPauseOnLeave(backgroundPlay = true, inPip = false))
        assertFalse(BackgroundPlayback.shouldPauseOnLeave(backgroundPlay = true, inPip = true))
    }

    @Test
    fun videoIsDroppedOnlyWhenPlayingOnWithNothingOnScreen() {
        assertTrue(BackgroundPlayback.shouldDropVideo(backgroundPlay = true, audioOnlyInBackground = true, inPip = false))
        // A PiP window shows the video, so it keeps it.
        assertFalse(BackgroundPlayback.shouldDropVideo(backgroundPlay = true, audioOnlyInBackground = true, inPip = true))
        // The setting is off: keep downloading video, as before.
        assertFalse(BackgroundPlayback.shouldDropVideo(backgroundPlay = true, audioOnlyInBackground = false, inPip = false))
        // Without background play leaving pauses, so there is nothing to switch.
        assertFalse(BackgroundPlayback.shouldDropVideo(backgroundPlay = false, audioOnlyInBackground = true, inPip = false))
    }

    @Test
    fun streamTitleLeadsWithChannelNameUnderneath() {
        val nowPlaying = NowPlaying.of("ninja", channel(displayName = "Ninja"), stream(title = "  Ranked grind  "))
        assertEquals("Ranked grind", nowPlaying.title)
        assertEquals("Ninja", nowPlaying.artist)
        assertEquals("https://cdn/ninja.png", nowPlaying.artworkUrl)
    }

    @Test
    fun missingTitleFallsBackToNameWithCategoryUnderneath() {
        val nowPlaying = NowPlaying.of("ninja", channel(displayName = "Ninja"), stream(title = " ", gameName = "Fortnite"))
        assertEquals("Ninja", nowPlaying.title)
        assertEquals("Fortnite", nowPlaying.artist)
    }

    @Test
    fun failedLookupsStillNameTheChannel() {
        val nowPlaying = NowPlaying.of("ninja", channel = null, stream = null)
        assertEquals("ninja", nowPlaying.title)
        assertNull(nowPlaying.artist)
        assertNull(nowPlaying.artworkUrl)
    }

    @Test
    fun streamUserNameCoversAMissingChannel() {
        val nowPlaying = NowPlaying.of("ninja", channel = null, stream = stream(title = "Live"))
        assertEquals("Ninja", nowPlaying.artist)
    }

    private fun channel(displayName: String) = ChannelInfo(
        id = "1",
        login = "ninja",
        displayName = displayName,
        profileImageUrl = "https://cdn/ninja.png",
    )

    private fun stream(title: String, gameName: String = "") = StreamInfo(
        id = "s1",
        userId = "1",
        userLogin = "ninja",
        userName = "Ninja",
        gameName = gameName,
        title = title,
    )
}
