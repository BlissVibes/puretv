package com.puretv.twitch.core.emotes

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** How a 7TV name paint fills the letters. */
enum class SevenTvPaintFunction { LINEAR_GRADIENT, RADIAL_GRADIENT, URL }

/** One colour stop of a paint gradient. [at] runs 0..1 along the gradient; [argb] is ready to draw. */
data class SevenTvPaintStop(val at: Float, val argb: Int)

/**
 * A 7TV name paint, as `cosmetic.create` defines it. Colours are already converted
 * from 7TV's packed RGBA to ARGB (see [sevenTvRgbaToArgb]).
 *
 * - [angle] is a CSS gradient angle in degrees: 0 points up, 90 points right.
 * - [repeat] makes the stops repeat along the line (a CSS repeating gradient).
 * - [ellipse] is true for an elliptical radial gradient, false for a circle.
 * - [argb] is the paint's own flat colour, when it has one.
 *
 * Image ([SevenTvPaintFunction.URL]) paints keep no image: PureTV draws them in
 * their own colour or first stop, so nothing extra is downloaded.
 */
data class SevenTvPaint(
    val id: String,
    val name: String,
    val function: SevenTvPaintFunction,
    val angle: Int = 0,
    val repeat: Boolean = false,
    val ellipse: Boolean = false,
    val argb: Int? = null,
    val stops: List<SevenTvPaintStop> = emptyList(),
)

/** A cosmetic change pushed over the 7TV EventAPI that matters to name paints. */
sealed class SevenTvCosmeticEvent {
    /** `cosmetic.create` for a paint: its definition. */
    data class PaintDefined(val paint: SevenTvPaint) : SevenTvCosmeticEvent()

    /** `entitlement.create` for a paint: [twitchUserId] now wears [paintId]. */
    data class PaintGranted(val twitchUserId: String, val paintId: String) : SevenTvCosmeticEvent()

    /** `entitlement.delete` for a paint: [twitchUserId] no longer wears [paintId]. */
    data class PaintRevoked(val twitchUserId: String, val paintId: String) : SevenTvCosmeticEvent()
}

/**
 * 7TV packs colours as RGBA in a signed 32-bit int (`255` is opaque black, `-1`
 * opaque white). Drawing code wants ARGB, so move the alpha byte to the top.
 */
fun sevenTvRgbaToArgb(rgba: Int): Int = (rgba ushr 8) or (rgba shl 24)

internal const val TYPE_COSMETIC_CREATE = "cosmetic.create"
internal const val TYPE_ENTITLEMENT_CREATE = "entitlement.create"
internal const val TYPE_ENTITLEMENT_DELETE = "entitlement.delete"

/**
 * Reads the `body` of a `cosmetic.create`, `entitlement.create` or
 * `entitlement.delete` dispatch. Only paints count: badges, emote sets and
 * anything unreadable give null. Entitlements are keyed by the user's Twitch id
 * (from their TWITCH connection), because that is what chat's `user-id` tag carries;
 * the 7TV user id is never used.
 */
internal fun parseSevenTvCosmetic(type: String, body: JsonObject): SevenTvCosmeticEvent? = runCatching {
    val obj = body["object"]?.jsonObject ?: return null
    if (obj.string("kind") != "PAINT") return null
    when (type) {
        TYPE_COSMETIC_CREATE -> obj["data"]?.jsonObject?.toPaint()?.let { SevenTvCosmeticEvent.PaintDefined(it) }
        TYPE_ENTITLEMENT_CREATE, TYPE_ENTITLEMENT_DELETE -> {
            val paintId = obj.string("ref_id")?.ifBlank { null } ?: return null
            val twitchId = obj["user"]?.jsonObject?.twitchConnectionId() ?: return null
            if (type == TYPE_ENTITLEMENT_CREATE) SevenTvCosmeticEvent.PaintGranted(twitchId, paintId)
            else SevenTvCosmeticEvent.PaintRevoked(twitchId, paintId)
        }
        else -> null
    }
}.getOrNull()

private fun JsonObject.toPaint(): SevenTvPaint? {
    val id = string("id")?.ifBlank { null } ?: return null
    val function = when (string("function")) {
        "LINEAR_GRADIENT" -> SevenTvPaintFunction.LINEAR_GRADIENT
        "RADIAL_GRADIENT" -> SevenTvPaintFunction.RADIAL_GRADIENT
        "URL" -> SevenTvPaintFunction.URL
        else -> return null
    }
    val stops = this["stops"]?.jsonArray.orEmpty().mapNotNull { entry ->
        val stop = entry.jsonObject
        val at = stop["at"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
        val color = stop.color("color") ?: return@mapNotNull null
        SevenTvPaintStop(at, color)
    }
    return SevenTvPaint(
        id = id,
        name = string("name").orEmpty(),
        function = function,
        angle = this["angle"]?.jsonPrimitive?.intOrNull ?: 0,
        repeat = this["repeat"]?.jsonPrimitive?.booleanOrNull ?: false,
        ellipse = string("shape") == "ellipse",
        argb = color("color"),
        stops = stops,
    )
}

/** The id of the user's TWITCH connection, or null when they have none. */
private fun JsonObject.twitchConnectionId(): String? =
    this["connections"]?.jsonArray.orEmpty()
        .map { it.jsonObject }
        .firstOrNull { it.string("platform") == "TWITCH" }
        ?.string("id")
        ?.ifBlank { null }

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

// Read as a long and narrow it, so the same bits come out whether 7TV sends the
// colour signed (-1) or unsigned (4294967295). JSON null gives null.
private fun JsonObject.color(key: String): Int? =
    this[key]?.jsonPrimitive?.longOrNull?.toInt()?.let(::sevenTvRgbaToArgb)

/**
 * Which paint each chatter wears, learned from the 7TV EventAPI. Paint definitions
 * and entitlements are kept apart and joined on read, because 7TV may send a
 * user's entitlement before (or long after) the paint's definition.
 *
 * Bounded both ways: the [maxUsers] most recently granted users and the
 * [maxPaints] most recently defined paints are kept; older ones are forgotten, so
 * a long session on a huge chat cannot grow without limit.
 *
 * Not thread safe: one coroutine applies events, then hands the immutable
 * [snapshot] to the UI.
 */
class SevenTvPaintStore(
    private val maxUsers: Int = DEFAULT_MAX_USERS,
    private val maxPaints: Int = DEFAULT_MAX_PAINTS,
) {
    // Insertion ordered: re-inserting on every write keeps the oldest entry first,
    // which is the one to evict. (Common Kotlin has no access-ordered LinkedHashMap.)
    private val paints = LinkedHashMap<String, SevenTvPaint>()
    private val paintIdByUser = LinkedHashMap<String, String>()

    /** Applies [event]; true when what a chatter looks like may have changed. */
    fun apply(event: SevenTvCosmeticEvent): Boolean = when (event) {
        is SevenTvCosmeticEvent.PaintDefined -> {
            val changed = paints[event.paint.id] != event.paint
            paints.remove(event.paint.id)
            paints[event.paint.id] = event.paint
            trim(paints, maxPaints)
            changed
        }
        is SevenTvCosmeticEvent.PaintGranted -> {
            val changed = paintIdByUser[event.twitchUserId] != event.paintId
            paintIdByUser.remove(event.twitchUserId)
            paintIdByUser[event.twitchUserId] = event.paintId
            trim(paintIdByUser, maxUsers)
            changed
        }
        // Only the paint being revoked: switching paints can arrive as a grant of
        // the new one followed by a revoke of the old one, which must not undo
        // the new paint.
        is SevenTvCosmeticEvent.PaintRevoked ->
            if (paintIdByUser[event.twitchUserId] == event.paintId) {
                paintIdByUser.remove(event.twitchUserId)
                true
            } else {
                false
            }
    }

    /** The paint [twitchUserId] wears right now, or null. */
    fun paintFor(twitchUserId: String): SevenTvPaint? = paintIdByUser[twitchUserId]?.let { paints[it] }

    /** Who wears what, as an immutable map from Twitch user id to paint. Users whose
     *  paint has not been defined yet are left out until it is. */
    fun snapshot(): Map<String, SevenTvPaint> {
        val out = HashMap<String, SevenTvPaint>(paintIdByUser.size)
        for ((user, paintId) in paintIdByUser) paints[paintId]?.let { out[user] = it }
        return out
    }

    private fun <V> trim(map: LinkedHashMap<String, V>, max: Int) {
        val it = map.keys.iterator()
        while (map.size > max && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    companion object {
        const val DEFAULT_MAX_USERS = 4_096
        const val DEFAULT_MAX_PAINTS = 512
    }
}
