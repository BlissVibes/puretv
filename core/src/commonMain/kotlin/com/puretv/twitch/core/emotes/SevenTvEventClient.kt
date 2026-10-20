package com.puretv.twitch.core.emotes

import com.puretv.twitch.core.model.ChannelEmote
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
import kotlin.time.TimeSource

/** A live change to a channel's active 7TV emote set, pushed over the EventAPI. */
data class SevenTvEmoteDelta(
    /** Emotes added to the set (or renamed-in). Index keyed by emote name downstream. */
    val added: List<ChannelEmote> = emptyList(),
    /** Emotes removed from the set (or renamed-out). */
    val removed: List<ChannelEmote> = emptyList(),
    /** Twitch id of the channel this change was pushed for, so a late one can be told from the current channel's. */
    val channelTwitchId: String = "",
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
            // The emote-set id is stable for the session (a broadcaster swapping sets is
            // signalled separately and is out of scope), but the lookup can fail on a
            // transient 7tv.io error, so it is retried on every dial until it lands.
            var setId: String? = null
            var setResolved = false
            val backoff = ReconnectBackoff()
            var lookupAttempts = 0
            while (isActive) {
                if (!setResolved) {
                    try {
                        setId = fetchEmoteSetId(channelTwitchId)
                        setResolved = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Retry on the next pass, but not for the whole stream: after a few
                        // failures treat the channel as having no 7TV set.
                        if (++lookupAttempts >= MAX_LOOKUP_ATTEMPTS) setResolved = true
                    }
                }
                val subscriptions = buildList {
                    setId?.let { add(emoteSetSubscribeFrame(it)) }
                    if (cosmetics) COSMETIC_TYPES.forEach { add(channelSubscribeFrame(it, channelTwitchId)) }
                }
                // A channel with no linked set and no cosmetics has nothing to listen for.
                if (setResolved && subscriptions.isEmpty()) return@launch
                if (subscriptions.isNotEmpty()) {
                    val dialedAt = TimeSource.Monotonic.markNow()
                    try {
                        httpClient.webSocket(WS_ENDPOINT) {
                            // Subscribe right away (again on every redial); the server
                            // tolerates SUBSCRIBE sent before we read its HELLO and
                            // replies with an ACK.
                            subscriptions.forEach { send(Frame.Text(it)) }
                            // Without the set id this socket only carries cosmetics, so
                            // cut it short to look the set up again.
                            val idleMs = if (setResolved) READ_IDLE_MS else LOOKUP_RETRY_MS
                            while (isActive) {
                                // Server HEARTBEATs about every 48s; no frame for the idle
                                // window means a half-open socket, so break to reconnect.
                                val frame = withTimeoutOrNull(idleMs) {
                                    incoming.receiveCatching().getOrNull()
                                } ?: break
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    // A subscribe ACK or a dispatch proves the connection is
                                    // healthy; a socket that opens (HELLO) and closes straight
                                    // away keeps backing off.
                                    if (isHealthyFrame(text)) backoff.onHealthy()
                                    when (val event = parseFrame(text, setId)) {
                                        is SevenTvEmoteDelta ->
                                            if (event.added.isNotEmpty() || event.removed.isNotEmpty()) _updates.emit(event.copy(channelTwitchId = channelTwitchId))
                                        is SevenTvCosmeticEvent -> if (cosmetics) _cosmetics.emit(event)
                                    }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e // disconnect()/scope cancellation, do NOT reconnect
                    } catch (_: Exception) {
                        // fall through to backoff + redial
                    }
                    // A connection that stayed up a while was fine even if it was quiet.
                    backoff.onSessionEnded(dialedAt.elapsedNow().inWholeMilliseconds)
                }
                if (!isActive) break
                delay(backoff.nextDelayMs(Random.nextLong(0, BACKOFF_JITTER_MS)))
            }
        }
    }

    /** Non-suspending (called from ViewModel onCleared, like TwitchChatClient.disconnect). */
    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
    }

    private suspend fun fetchEmoteSetId(channelTwitchId: String): String? {
        val response = httpClient.get("https://7tv.io/v3/users/twitch/$channelTwitchId")
        return parseEmoteSetLookup(response.status.value, response.bodyAsText(), json)
    }

    private fun isHealthyFrame(text: String): Boolean {
        val op = runCatching { json.parseToJsonElement(text).jsonObject["op"]?.jsonPrimitive?.intOrNull }.getOrNull()
        return op == OP_DISPATCH || op == OP_ACK
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
        const val OP_ACK = 5
        const val MAX_LOOKUP_ATTEMPTS = 5
        val COSMETIC_TYPES = listOf(TYPE_COSMETIC_CREATE, TYPE_ENTITLEMENT_CREATE, TYPE_ENTITLEMENT_DELETE)
        const val BACKOFF_JITTER_MS = 500L
        const val LOOKUP_RETRY_MS = 30_000L
        // > 7TV's heartbeat (about every 48s, `heartbeat_interval` in HELLO) with
        // slack, so a quiet-but-healthy subscription isn't mistaken for a dead socket.
        const val READ_IDLE_MS = 90_000L
    }
}

/**
 * Resolves the 7TV user lookup into the channel's emote-set id. A 404 or a body that
 * is not the expected JSON means the channel has no 7TV set (null, settled for good);
 * any other failure status throws so the caller retries.
 */
internal fun parseEmoteSetLookup(status: Int, body: String, json: Json): String? {
    if (status == 404) return null
    if (status !in 200..299) error("7TV lookup failed with HTTP $status")
    val raw = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    return raw["emote_set"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
}

/**
 * Reconnect delay for [SevenTvEventClient]: doubles up to a cap, and goes back to the
 * start only once the connection has proved healthy since the last dial (the server
 * acknowledged a subscription or pushed an event, or the socket stayed up for a while).
 */
internal class ReconnectBackoff(
    private val initialMs: Long = 1_000L,
    private val maxMs: Long = 30_000L,
    private val healthyAfterMs: Long = 10_000L,
) {
    private var currentMs = initialMs
    private var healthy = false

    /** The server acknowledged a subscription or pushed an event on this connection. */
    fun onHealthy() {
        healthy = true
    }

    /** A connection that lived for [livedMs] counts as healthy once it lasted long enough. */
    fun onSessionEnded(livedMs: Long) {
        if (livedMs >= healthyAfterMs) healthy = true
    }

    /** The delay to wait before the next dial, plus [jitterMs]; advances the backoff. */
    fun nextDelayMs(jitterMs: Long = 0L): Long {
        if (healthy) currentMs = initialMs
        healthy = false
        val delayMs = currentMs + jitterMs
        currentMs = (currentMs * 2).coerceAtMost(maxMs)
        return delayMs
    }
}
