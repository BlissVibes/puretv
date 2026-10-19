package com.puretv.twitch.core.adblock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Shaped like two refreshes of a live 360p playlist taken four seconds apart
 * (2026-10-19, URLs replaced): the second lists the first one's prefetch
 * segments and adds two new hints.
 */
class TwitchPrefetchTest {

    @Test
    fun prefetchHintsBecomeSegmentsAtTheEnd() {
        val promoted = TwitchPrefetch.promote(FIRST)
        assertFalse("#EXT-X-TWITCH-PREFETCH" in promoted)
        assertEquals(
            listOf("seg-7231", "seg-7232", "seg-7233", "seg-7234", "seg-7235", "seg-7236"),
            uris(promoted),
        )
        assertTrue(promoted.endsWith("#EXTINF:2.000,live\nhttps://cdn.invalid/seg-7236\n"), promoted)
        // Headers and the listed segments are untouched.
        assertTrue(promoted.startsWith(FIRST.substringBefore("#EXT-X-TWITCH-PREFETCH")))
    }

    @Test
    fun theEndOfThePlaylistNeverMovesBackAcrossRefreshes() {
        val ends = listOf(FIRST, SECOND).map { lastSequence(TwitchPrefetch.promote(it)) }
        assertEquals(listOf(7236L, 7238L), ends)
        // Promoted segment N is the very URL Twitch lists as N next time.
        val promotedFirst = sequenceToUri(TwitchPrefetch.promote(FIRST))
        val listedSecond = sequenceToUri(TwitchPrefetch.strip(SECOND))
        promotedFirst.keys.intersect(listedSecond.keys).forEach { seq ->
            assertEquals(listedSecond[seq], promotedFirst[seq], "sequence $seq")
        }
    }

    @Test
    fun aHintThatIsAlreadyListedIsNotRepeated() {
        val playlist = FIRST + "#EXT-X-TWITCH-PREFETCH:https://cdn.invalid/seg-7234\n"
        assertEquals(1, uris(TwitchPrefetch.promote(playlist)).count { it == "seg-7234" })
    }

    @Test
    fun aPlaylistWithoutHintsIsLeftAlone() {
        val plain = TwitchPrefetch.strip(FIRST)
        assertEquals(plain, TwitchPrefetch.promote(plain))
        assertEquals(plain, TwitchPrefetch.strip(plain))
    }

    @Test
    fun stripRemovesOnlyTheHints() {
        val stripped = TwitchPrefetch.strip(FIRST)
        assertFalse("#EXT-X-TWITCH-PREFETCH" in stripped)
        assertEquals(listOf("seg-7231", "seg-7232", "seg-7233", "seg-7234"), uris(stripped))
    }

    private fun uris(playlist: String) =
        playlist.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringAfterLast('/') }

    private fun sequenceToUri(playlist: String): Map<Long, String> {
        val first = playlist.lines().first { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }.substringAfter(':').toLong()
        return playlist.lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .mapIndexed { i, uri -> first + i to uri }.toMap()
    }

    private fun lastSequence(playlist: String): Long = sequenceToUri(playlist).keys.max()

    private companion object {
        fun window(firstSequence: Long, listed: Int, prefetch: Int): String = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:6")
            appendLine("#EXT-X-MEDIA-SEQUENCE:$firstSequence")
            appendLine("#EXT-X-TWITCH-LIVE-SEQUENCE:$firstSequence")
            appendLine("#EXT-X-TWITCH-ELAPSED-SECS:${firstSequence * 2}.000")
            for (i in 0 until listed) {
                appendLine("#EXT-X-PROGRAM-DATE-TIME:2026-10-19T21:37:${10 + 2 * i}.618Z")
                appendLine("#EXTINF:2.000,live")
                appendLine("https://cdn.invalid/seg-${firstSequence + i}")
            }
            for (i in 0 until prefetch) {
                appendLine("#EXT-X-TWITCH-PREFETCH:https://cdn.invalid/seg-${firstSequence + listed + i}")
            }
        }

        val FIRST = window(firstSequence = 7_231, listed = 4, prefetch = 2)
        val SECOND = window(firstSequence = 7_233, listed = 4, prefetch = 2)
    }
}
