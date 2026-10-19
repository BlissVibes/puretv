package com.puretv.twitch.core.notify

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The broadcast last seen for one channel, and when it was last in a successful poll. */
@Serializable
data class SeenStream(val streamId: String, val lastSeenAtMs: Long)

/**
 * What [GoLiveDetector] remembers between polls, persisted so an app restart never
 * re-announces a broadcast it already saw. Keys are lowercased logins. [userId] ties
 * the state to one account: a different account signing in starts over silently.
 */
@Serializable
data class LiveNotifyState(
    val userId: String = "",
    val seeded: Boolean = false,
    val seen: Map<String, SeenStream> = emptyMap(),
    val lastNotifiedAtMs: Map<String, Long> = emptyMap(),
)

object LiveNotifyStateCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(state: LiveNotifyState): String = json.encodeToString(LiveNotifyState.serializer(), state)

    /** Corrupt or missing input degrades to an empty state, which simply re-seeds silently. */
    fun decode(text: String?): LiveNotifyState =
        text?.let { runCatching { json.decodeFromString(LiveNotifyState.serializer(), it) }.getOrNull() }
            ?: LiveNotifyState()
}

/** Where each app keeps [LiveNotifyState]: a JSON file on Windows, DataStore on phone and TV. */
interface LiveNotifyStateStore {
    suspend fun load(): LiveNotifyState
    suspend fun save(state: LiveNotifyState)
}

/** Keeps the state in memory only. For tests, and as a safe default. */
class InMemoryLiveNotifyStateStore(private var state: LiveNotifyState = LiveNotifyState()) : LiveNotifyStateStore {
    var saves: Int = 0
        private set

    override suspend fun load(): LiveNotifyState = state

    override suspend fun save(state: LiveNotifyState) {
        this.state = state
        saves++
    }
}
