package com.puretv.twitch.core.notify

import com.puretv.twitch.core.model.StreamInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoLiveDetectorTest {
    private fun s(login: String, id: String, startedAt: String = "") =
        StreamInfo(id = id, userId = "u-$login", userLogin = login, userName = login, startedAt = startedAt)
    private val min = 60_000L

    @Test fun firstRunSeedsSilently() {
        val d = GoLiveDetector.evaluate(LiveNotifyState(), "me", listOf(s("a", "1"), s("b", "2")), nowMs = 0, enabled = true)
        assertTrue(d.toNotify.isEmpty())
        assertEquals(setOf("a", "b"), d.nextState.seen.keys)
        assertTrue(d.nextState.seeded)
    }

    @Test fun newBroadcastNotifiesOnce() {
        val seeded = GoLiveDetector.evaluate(LiveNotifyState(), "me", listOf(s("a", "1")), 0, enabled = true).nextState
        val d = GoLiveDetector.evaluate(seeded, "me", listOf(s("a", "1"), s("b", "9")), 2 * min, enabled = true)
        assertEquals(listOf("b"), d.toNotify.map { it.userLogin })
        val again = GoLiveDetector.evaluate(d.nextState, "me", listOf(s("a", "1"), s("b", "9")), 4 * min, enabled = true)
        assertTrue(again.toNotify.isEmpty(), "same stream id must not re-notify")
    }

    @Test fun briefDropWithSameIdDoesNotRenotify() {
        var st = GoLiveDetector.evaluate(LiveNotifyState(), "me", listOf(s("a", "1")), 0, enabled = true).nextState
        st = GoLiveDetector.evaluate(st, "me", emptyList(), 2 * min, enabled = true).nextState
        val back = GoLiveDetector.evaluate(st, "me", listOf(s("a", "1")), 4 * min, enabled = true)
        assertTrue(back.toNotify.isEmpty())
    }

    @Test fun seenEntriesExpireAfterThirtyMinutes() {
        var st = GoLiveDetector.evaluate(LiveNotifyState(), "me", listOf(s("a", "1")), 0, enabled = true).nextState
        st = GoLiveDetector.evaluate(st, "me", emptyList(), 31 * min, enabled = true).nextState
        assertTrue("a" !in st.seen)
    }

    @Test fun longGapWithTheSameStreamStillLiveDoesNotRenotify() {
        // A phone in Doze or a closed desktop app: the next poll comes hours later and
        // the very same broadcast is still on. It was already announced (or seeded).
        val st = GoLiveDetector.evaluate(LiveNotifyState(), "me", listOf(s("a", "1")), 0, enabled = true).nextState
        val later = GoLiveDetector.evaluate(st, "me", listOf(s("a", "1")), 120 * min, enabled = true)
        assertTrue(later.toNotify.isEmpty())
        assertEquals("1", later.nextState.seen["a"]?.streamId)
    }

    @Test fun cooldownSuppressesFlappingNewIds() {
        val st = GoLiveDetector.evaluate(LiveNotifyState(), "me", emptyList(), 0, enabled = true).nextState
        val first = GoLiveDetector.evaluate(st, "me", listOf(s("a", "1")), 1 * min, enabled = true)
        assertEquals(1, first.toNotify.size)
        val flap = GoLiveDetector.evaluate(first.nextState, "me", listOf(s("a", "2")), 5 * min, enabled = true)
        assertTrue(flap.toNotify.isEmpty(), "inside the 10 minute cooldown")
        val later = GoLiveDetector.evaluate(flap.nextState, "me", listOf(s("a", "3")), 20 * min, enabled = true)
        assertEquals(1, later.toNotify.size)
    }

    @Test fun accountSwitchReseeds() {
        val st = GoLiveDetector.evaluate(LiveNotifyState(), "me", listOf(s("a", "1")), 0, enabled = true).nextState
        val other = GoLiveDetector.evaluate(st, "someoneElse", listOf(s("z", "5")), 1 * min, enabled = true)
        assertTrue(other.toNotify.isEmpty())
        assertEquals("someoneElse", other.nextState.userId)
    }

    @Test fun onlyWatchedChannelsAreAnnounced() {
        val st = GoLiveDetector.evaluate(LiveNotifyState(), "me", emptyList(), 0, enabled = true, watched = setOf("quiet", "loud")).nextState
        val d = GoLiveDetector.evaluate(st, "me", listOf(s("Loud", "1"), s("stranger", "2")), min, enabled = true, watched = setOf("quiet", "loud"))
        assertEquals(listOf("Loud"), d.toNotify.map { it.userLogin })
    }

    @Test fun aNewlyWatchedChannelIsSeededNotAnnounced() {
        val st = GoLiveDetector.evaluate(LiveNotifyState(), "me", emptyList(), 0, enabled = true, watched = setOf("a")).nextState
        val added = GoLiveDetector.evaluate(st, "me", listOf(s("b", "1")), min, enabled = true, watched = setOf("a", "b"))
        assertTrue(added.toNotify.isEmpty())
        assertEquals(setOf("a", "b"), added.nextState.watched)
        val next = GoLiveDetector.evaluate(added.nextState, "me", listOf(s("b", "2")), 2 * min, enabled = true, watched = setOf("a", "b"))
        assertEquals(listOf("b"), next.toNotify.map { it.userLogin })
    }

    @Test fun disabledNeverNotifyButStillTrack() {
        val st = GoLiveDetector.evaluate(LiveNotifyState(), "me", emptyList(), 0, enabled = true).nextState
        val off = GoLiveDetector.evaluate(st, "me", listOf(s("b", "2")), min, enabled = false)
        assertTrue(off.toNotify.isEmpty())
        assertTrue("b" in off.nextState.seen, "turning alerts on later must not announce this stream")
    }

    @Test fun theFirstPollAfterAlertsWereOffSeedsSilently() {
        val st = GoLiveDetector.evaluate(LiveNotifyState(), "me", emptyList(), 0, enabled = true).nextState
        val off = GoLiveDetector.evaluate(st, "me", listOf(s("a", "1")), min, enabled = false).nextState
        val on = GoLiveDetector.evaluate(off, "me", listOf(s("a", "1"), s("b", "2")), 40 * min, enabled = true)
        assertTrue(on.toNotify.isEmpty(), "b went live while alerts were off")
        val next = GoLiveDetector.evaluate(on.nextState, "me", listOf(s("a", "1"), s("b", "2"), s("c", "3")), 42 * min, enabled = true)
        assertEquals(listOf("c"), next.toNotify.map { it.userLogin })
    }

    @Test fun seedingAgainKeepsTheSameAccountsCooldowns() {
        val st = GoLiveDetector.evaluate(LiveNotifyState(), "me", emptyList(), 0, enabled = true).nextState
        val announced = GoLiveDetector.evaluate(st, "me", listOf(s("a", "1")), min, enabled = true).nextState
        val reseeded = GoLiveDetector.evaluate(announced.copy(seeded = false), "me", emptyList(), 2 * min, enabled = true).nextState
        val flap = GoLiveDetector.evaluate(reseeded, "me", listOf(s("a", "2")), 4 * min, enabled = true)
        assertTrue(flap.toNotify.isEmpty(), "still inside the cooldown from the first announcement")
    }

    @Test fun streamsLiveForAgesAreNotAnnouncedLate() {
        // 2026-10-19T10:00:00Z, and "now" three hours later: the app was closed while
        // this broadcast started, so it did not just go live.
        val startedMs = 1_792_404_000_000L
        val st = GoLiveDetector.evaluate(LiveNotifyState(), "me", emptyList(), startedMs - min, enabled = true).nextState
        val stale = GoLiveDetector.evaluate(st, "me", listOf(s("a", "1", "2026-10-19T10:00:00Z")), startedMs + 180 * min, enabled = true)
        assertTrue(stale.toNotify.isEmpty())
        assertTrue("a" in stale.nextState.seen)
        val fresh = GoLiveDetector.evaluate(st, "me", listOf(s("b", "2", "2026-10-19T10:00:00Z")), startedMs + 40 * min, enabled = true)
        assertEquals(listOf("b"), fresh.toNotify.map { it.userLogin })
    }

    @Test fun parsesTwitchTimestamps() {
        assertEquals(1_792_404_000_000L, GoLiveDetector.parseStartedAtMs("2026-10-19T10:00:00Z"))
        assertEquals(0L, GoLiveDetector.parseStartedAtMs("1970-01-01T00:00:00Z"))
        assertEquals(951_782_400_000L, GoLiveDetector.parseStartedAtMs("2000-02-29T00:00:00Z"))
        assertNull(GoLiveDetector.parseStartedAtMs(""))
        assertNull(GoLiveDetector.parseStartedAtMs("yesterday"))
    }

    @Test fun stateRoundTripsThroughJson() {
        val st = GoLiveDetector.evaluate(LiveNotifyState(), "me", listOf(s("a", "1")), 0, enabled = true).nextState
        assertEquals(st, LiveNotifyStateCodec.decode(LiveNotifyStateCodec.encode(st)))
        assertEquals(LiveNotifyState(), LiveNotifyStateCodec.decode("{not json"))
        assertEquals(LiveNotifyState(), LiveNotifyStateCodec.decode(null))
    }
}
