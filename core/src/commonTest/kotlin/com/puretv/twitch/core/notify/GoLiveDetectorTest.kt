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
        val d = GoLiveDetector.evaluate(LiveNotifyState(), "me", listOf(s("a", "1"), s("b", "2")), nowMs = 0, muted = emptySet(), enabled = true)
        assertTrue(d.toNotify.isEmpty())
        assertEquals(setOf("a", "b"), d.nextState.seen.keys)
        assertTrue(d.nextState.seeded)
    }

    @Test fun newBroadcastNotifiesOnce() {
        val seeded = GoLiveDetector.evaluate(LiveNotifyState(), "me", listOf(s("a", "1")), 0, emptySet(), true).nextState
        val d = GoLiveDetector.evaluate(seeded, "me", listOf(s("a", "1"), s("b", "9")), 2 * min, emptySet(), true)
        assertEquals(listOf("b"), d.toNotify.map { it.userLogin })
        val again = GoLiveDetector.evaluate(d.nextState, "me", listOf(s("a", "1"), s("b", "9")), 4 * min, emptySet(), true)
        assertTrue(again.toNotify.isEmpty(), "same stream id must not re-notify")
    }

    @Test fun briefDropWithSameIdDoesNotRenotify() {
        var st = GoLiveDetector.evaluate(LiveNotifyState(), "me", listOf(s("a", "1")), 0, emptySet(), true).nextState
        st = GoLiveDetector.evaluate(st, "me", emptyList(), 2 * min, emptySet(), true).nextState
        val back = GoLiveDetector.evaluate(st, "me", listOf(s("a", "1")), 4 * min, emptySet(), true)
        assertTrue(back.toNotify.isEmpty())
    }

    @Test fun seenEntriesExpireAfterThirtyMinutes() {
        var st = GoLiveDetector.evaluate(LiveNotifyState(), "me", listOf(s("a", "1")), 0, emptySet(), true).nextState
        st = GoLiveDetector.evaluate(st, "me", emptyList(), 31 * min, emptySet(), true).nextState
        assertTrue("a" !in st.seen)
    }

    @Test fun longGapWithTheSameStreamStillLiveDoesNotRenotify() {
        // A phone in Doze or a closed desktop app: the next poll comes hours later and
        // the very same broadcast is still on. It was already announced (or seeded).
        val st = GoLiveDetector.evaluate(LiveNotifyState(), "me", listOf(s("a", "1")), 0, emptySet(), true).nextState
        val later = GoLiveDetector.evaluate(st, "me", listOf(s("a", "1")), 120 * min, emptySet(), true)
        assertTrue(later.toNotify.isEmpty())
        assertEquals("1", later.nextState.seen["a"]?.streamId)
    }

    @Test fun cooldownSuppressesFlappingNewIds() {
        val st = GoLiveDetector.evaluate(LiveNotifyState(), "me", emptyList(), 0, emptySet(), true).nextState
        val first = GoLiveDetector.evaluate(st, "me", listOf(s("a", "1")), 1 * min, emptySet(), true)
        assertEquals(1, first.toNotify.size)
        val flap = GoLiveDetector.evaluate(first.nextState, "me", listOf(s("a", "2")), 5 * min, emptySet(), true)
        assertTrue(flap.toNotify.isEmpty(), "inside the 10 minute cooldown")
        val later = GoLiveDetector.evaluate(flap.nextState, "me", listOf(s("a", "3")), 20 * min, emptySet(), true)
        assertEquals(1, later.toNotify.size)
    }

    @Test fun accountSwitchReseeds() {
        val st = GoLiveDetector.evaluate(LiveNotifyState(), "me", listOf(s("a", "1")), 0, emptySet(), true).nextState
        val other = GoLiveDetector.evaluate(st, "someoneElse", listOf(s("z", "5")), 1 * min, emptySet(), true)
        assertTrue(other.toNotify.isEmpty())
        assertEquals("someoneElse", other.nextState.userId)
    }

    @Test fun mutedAndDisabledNeverNotifyButStillTrack() {
        val st = GoLiveDetector.evaluate(LiveNotifyState(), "me", emptyList(), 0, emptySet(), true).nextState
        val muted = GoLiveDetector.evaluate(st, "me", listOf(s("Loud", "1")), min, setOf("loud"), true)
        assertTrue(muted.toNotify.isEmpty())
        assertTrue("loud" in muted.nextState.seen)
        val off = GoLiveDetector.evaluate(st, "me", listOf(s("b", "2")), min, emptySet(), enabled = false)
        assertTrue(off.toNotify.isEmpty())
        assertTrue("b" in off.nextState.seen, "turning alerts on later must not announce this stream")
    }

    @Test fun streamsLiveForAgesAreNotAnnouncedLate() {
        // 2026-10-19T10:00:00Z, and "now" three hours later: the app was closed while
        // this broadcast started, so it did not just go live.
        val startedMs = 1_792_404_000_000L
        val st = GoLiveDetector.evaluate(LiveNotifyState(), "me", emptyList(), startedMs - min, emptySet(), true).nextState
        val stale = GoLiveDetector.evaluate(st, "me", listOf(s("a", "1", "2026-10-19T10:00:00Z")), startedMs + 180 * min, emptySet(), true)
        assertTrue(stale.toNotify.isEmpty())
        assertTrue("a" in stale.nextState.seen)
        val fresh = GoLiveDetector.evaluate(st, "me", listOf(s("b", "2", "2026-10-19T10:00:00Z")), startedMs + 40 * min, emptySet(), true)
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
        val st = GoLiveDetector.evaluate(LiveNotifyState(), "me", listOf(s("a", "1")), 0, emptySet(), true).nextState
        assertEquals(st, LiveNotifyStateCodec.decode(LiveNotifyStateCodec.encode(st)))
        assertEquals(LiveNotifyState(), LiveNotifyStateCodec.decode("{not json"))
        assertEquals(LiveNotifyState(), LiveNotifyStateCodec.decode(null))
    }
}
