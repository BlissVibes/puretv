package com.puretv.twitch.core.emotes

import com.puretv.twitch.core.model.ChannelEmote
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/** A live change to a channel's active 7TV emote set, pushed over the EventAPI. */
data class SevenTvEmoteDelta(
    /** Emotes added to the set (or renamed-in). Index keyed by emote name downstream. */
    val added: List<ChannelEmote> = emptyList(),
    /** Emotes removed from the set (or renamed-out). */
    val removed: List<ChannelEmote> = emptyList(),
)

/**
 * SECTION 05.4 — 7TV EventAPI client (live emote updates).
 *
 * This is what makes chat "feel alive" like Chatterino7: when a broadcaster (or a
 * 7TV editor) adds/removes/renames an emote, 7TV pushes the change over a WebSocket
 * and it appears in chat WITHOUT a reconnect or app restart. We fetch the channel's
 * emotes once at join via [EmoteRepository]; this client keeps that set current.
 *
 * Protocol (https://github.com/SevenTV/EventAPI):
 *   - connect to [WS_ENDPOINT]; server opens with a HELLO (op 1)
 *   - send SUBSCRIBE (op 35) for `emote_set.update` keyed on the channel's set id
 *   - server streams DISPATCH (op 0) frames with `pushed` / `pulled` / `updated` lists
 *   - server sends periodic HEARTBEAT (op 2); a long read gap means a dead socket
 *
 * With cosmetics on (Windows only), it also subscribes to `cosmetic.create`,
 * `entitlement.create` and `entitlement.delete` in the channel's context and
 * streams name paints on [cosmetics]. Receive only: PureTV never posts a 7TV
 * presence, so 7TV learns nothing beyond the channel id it already gets for the
 * emote set. A channel context also brings other users' personal emote sets as
 * `emote_set.update`; only the channel's own set is trusted.
 *
 * Mirrors [com.puretv.twitch.core.chat.TwitchChatClient]'s reconnect-with-backoff
 * shape so a dropped socket silently self-heals for the rest of the channel session.
 */
class SevenTvEventClient(
    private val httpClient: HttpClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val _updates = MutableSharedFlow<SevenTvEmoteDelta>(replay = 0, extraBufferCapacity = 64)
    val updates: Flow<SevenTvEmoteDelta> = _updates.asSharedFlow()

    private val _cosmetics = MutableSharedFlow<SevenTvCosmeticEvent>(replay = 0, extraBufferCapacity = 256)

    /** Name paint definitions and grants for the channel; silent unless [connect] asked for cosmetics. */
    val cosmetics: Flow<SevenTvCosmeticEvent> = _cosmetics.asSharedFlow()

    private var connectionJob: Job? = null

    /**
     * Resolve [channelTwitchId]'s active 7TV emote-set id, then open the EventAPI
     * subscription and stream [updates] (and, with [cosmetics], name paints on
     * [SevenTvEventClient.cosmetics]). Cancels any previous subscription first, so
     * calling this on a channel switch cleanly re-targets. A channel with no linked
     * 7TV set gets no emote updates; without [cosmetics] that means no socket at all.
     */
    fun connect(channelTwitchId: String, cosmetics: Boolean = false) {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            // Resolve the emote-set id once per channel (it's stable for the session;
            // a broadcaster swapping sets is signalled separately and is out of scope).
            val setId = runCatching { fetchEmoteSetId(channelTwitchId) }.getOrNull()
            val subscriptions = buildList {
                if (setId != null) add(emoteSetSubscribeFrame(setId))
                if (cosmetics) COSMETIC_TYPES.forEach { add(channelSubscribeFrame(it, channelTwitchId)) }
            }
            if (subscriptions.isEmpty()) return@launch
            var backoffMs = INITIAL_BACKOFF_MS
            while (isActive) {
                try {
                    httpClient.webSocket(WS_ENDPOINT) {
                        backoffMs = INITIAL_BACKOFF_MS // a healthy connect resets the backoff
                        // Subscribe right away (again on every redial); the server
                        // tolerates SUBSCRIBE sent before we read its HELLO and
                        // replies with an ACK.
                        subscriptions.forEach { send(Frame.Text(it)) }
                        while (isActive) {
                            // Server HEARTBEATs about every 48s; no frame for READ_IDLE_MS
                            // means a half-open socket, so break to reconnect.
                            val frame = withTimeoutOrNull(READ_IDLE_MS) {
                                incoming.receiveCatching().getOrNull()
                            } ?: break
                            if (frame is Frame.Text) {
                                when (val event = parseFrame(frame.readText(), setId)) {
                                    is SevenTvEmoteDelta ->
                                        if (event.added.isNotEmpty() || event.removed.isNotEmpty()) _updates.emit(event)
                                    is SevenTvCosmeticEvent -> if (cosmetics) _cosmetics.emit(event)
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e // disconnect()/scope cancellation — do NOT reconnect
                } catch (_: Exception) {
                    // fall through to backoff + redial
                }
                if (!isActive) break
                delay(backoffMs + Random.nextLong(0, BACKOFF_JITTER_MS))
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /** Non-suspending (called from ViewModel onCleared, like TwitchChatClient.disconnect). */
    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
    }

    private suspend fun fetchEmoteSetId(channelTwitchId: String): String? {
        val raw: JsonObject = httpClient.get("https://7tv.io/v3/users/twitch/$channelTwitchId").body()
        return raw["emote_set"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
    }

    private fun emoteSetSubscribeFrame(setId: String): String =
        """{"op":$OP_SUBSCRIBE,"d":{"type":"$TYPE_EMOTE_SET_UPDATE","condition":{"object_id":"$setId"}}}"""

    private fun channelSubscribeFrame(type: String, channelTwitchId: String): String =
        """{"op":$OP_SUBSCRIBE,"d":{"type":"$type","condition":{"ctx":"channel","platform":"TWITCH","id":"$channelTwitchId"}}}"""

    /**
     * Parse an EventAPI text frame once and branch on its dispatch type: a
     * [SevenTvEmoteDelta] for the channel's own `emote_set.update`, a
     * [SevenTvCosmeticEvent] for a paint, null for everything else.
     */
    internal fun parseFrame(text: String, channelSetId: String?): Any? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if (root["op"]?.jsonPrimitive?.intOrNull != OP_DISPATCH) return null
        val d = root["d"]?.jsonObject ?: return null
        val type = d["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val body = d["body"]?.jsonObject ?: return null
        return when (type) {
            TYPE_EMOTE_SET_UPDATE -> emoteDelta(body, channelSetId)
            TYPE_COSMETIC_CREATE, TYPE_ENTITLEMENT_CREATE, TYPE_ENTITLEMENT_DELETE -> parseSevenTvCosmetic(type, body)
            else -> null
        }
    }

    /**
     * Parse an EventAPI text frame. Returns a delta only for an `emote_set.update`
     * DISPATCH (op 0) on [channelSetId]; HELLO/ACK/HEARTBEAT, other dispatch types
     * and other sets (someone's personal set) yield null, and so does every set
     * when the channel has none.
     *
     * `pushed` carries the new active emote under `value`; `pulled` carries the
     * removed one under `old_value`; a rename arrives as `updated` (both present) —
     * we treat the rename as remove-old + add-new so the name→emote index stays correct.
     */
    internal fun parseDispatch(text: String, channelSetId: String?): SevenTvEmoteDelta? =
        parseFrame(text, channelSetId) as? SevenTvEmoteDelta

    private fun emoteDelta(body: JsonObject, channelSetId: String?): SevenTvEmoteDelta? {
        // Only the channel's own set: other users' personal sets arrive here too.
        if (channelSetId == null || body["id"]?.jsonPrimitive?.contentOrNull != channelSetId) return null
        val added = mutableListOf<ChannelEmote>()
        val removed = mutableListOf<ChannelEmote>()
        emoteValues(body, "pushed", "value").forEach { added += it }
        emoteValues(body, "pulled", "old_value").forEach { removed += it }
        emoteValues(body, "updated", "old_value").forEach { removed += it }
        emoteValues(body, "updated", "value").forEach { added += it }
        return SevenTvEmoteDelta(added = added, removed = removed)
    }

    /** Pull the `emotes`-keyed active-emote objects out of a change list, mapping each
     *  via the shared [toSevenTvEmote] so live updates and the REST set parse identically. */
    private fun emoteValues(body: JsonObject, listKey: String, valueKey: String): List<ChannelEmote> =
        body[listKey]?.jsonArray.orEmpty().mapNotNull { entry ->
            runCatching {
                val obj = entry.jsonObject
                // Each change entry targets a field of the set; only "emotes" changes matter.
                if (obj["key"]?.jsonPrimitive?.contentOrNull != "emotes") return@mapNotNull null
                obj[valueKey]?.jsonObject?.toSevenTvEmote()
            }.getOrNull()
        }

    private companion object {
        const val WS_ENDPOINT = "wss://events.7tv.io/v3"
        const val TYPE_EMOTE_SET_UPDATE = "emote_set.update"
        const val OP_DISPATCH = 0
        const val OP_SUBSCRIBE = 35
        val COSMETIC_TYPES = listOf(TYPE_COSMETIC_CREATE, TYPE_ENTITLEMENT_CREATE, TYPE_ENTITLEMENT_DELETE)
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val BACKOFF_JITTER_MS = 500L
        // > 7TV's heartbeat (about every 48s, `heartbeat_interval` in HELLO) with
        // slack, so a quiet-but-healthy subscription isn't mistaken for a dead socket.
        const val READ_IDLE_MS = 90_000L
    }
}
