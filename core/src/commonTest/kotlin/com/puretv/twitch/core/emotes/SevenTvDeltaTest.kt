package com.puretv.twitch.core.emotes

import com.puretv.twitch.core.model.ChannelEmote
import com.puretv.twitch.core.model.EmoteProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SevenTvDeltaTest {

    private fun emote(name: String, provider: EmoteProvider = EmoteProvider.SEVENTV, id: String = name) =
        ChannelEmote(id = id, name = name, url = "https://cdn/$id", provider = provider, animated = false)

    @Test
    fun removalByNameOnlyTouchesSevenTvEmotes() {
        val current = listOf(emote("catJAM"), emote("catJAM", EmoteProvider.BTTV), emote("OMEGALUL", EmoteProvider.FFZ))
        val after = applySevenTvDelta(current, SevenTvEmoteDelta(removed = listOf(emote("catJAM"))))
        assertEquals(
            listOf(emote("catJAM", EmoteProvider.BTTV), emote("OMEGALUL", EmoteProvider.FFZ)),
            after,
        )
    }

    @Test
    fun aRepeatedAddIsNotInsertedTwice() {
        val current = listOf(emote("peepoHappy"))
        assertNull(applySevenTvDelta(current, SevenTvEmoteDelta(added = listOf(emote("peepoHappy", id = "other")))))
    }

    @Test
    fun twoCopiesOfTheSameAddInOneDeltaLandOnce() {
        val after = applySevenTvDelta(emptyList(), SevenTvEmoteDelta(added = listOf(emote("a"), emote("a"))))
        assertEquals(listOf(emote("a")), after)
    }

    @Test
    fun aRenameIsARemovePlusAnAdd() {
        val current = listOf(emote("oldName", id = "e1"), emote("other"))
        val after = applySevenTvDelta(
            current,
            SevenTvEmoteDelta(added = listOf(emote("newName", id = "e1")), removed = listOf(emote("oldName", id = "e1"))),
        )
        assertEquals(listOf(emote("other"), emote("newName", id = "e1")), after)
    }

    @Test
    fun anAddNamedLikeAnotherProvidersEmoteStillLands() {
        val current = listOf(emote("catJAM", EmoteProvider.BTTV))
        val after = applySevenTvDelta(current, SevenTvEmoteDelta(added = listOf(emote("catJAM"))))
        assertEquals(listOf(emote("catJAM", EmoteProvider.BTTV), emote("catJAM")), after)
    }

    @Test
    fun aDeltaThatChangesNothingReturnsNull() {
        val current = listOf(emote("a"))
        assertNull(applySevenTvDelta(current, SevenTvEmoteDelta(removed = listOf(emote("missing")))))
        assertNull(applySevenTvDelta(current, SevenTvEmoteDelta(added = listOf(emote("")))))
        assertNull(applySevenTvDelta(current, SevenTvEmoteDelta()))
    }
}
