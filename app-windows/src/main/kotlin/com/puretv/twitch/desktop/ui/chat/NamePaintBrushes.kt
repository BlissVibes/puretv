package com.puretv.twitch.desktop.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.puretv.twitch.core.emotes.SevenTvPaint
import com.puretv.twitch.core.emotes.SevenTvPaintFunction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How a 7TV name paint is drawn, worked out once per paint (see [NamePaintBrushes]).
 * Stops are sorted, clamped to 0..1 and already rescaled for repeating gradients,
 * so turning a spec into a brush is just wrapping it.
 */
sealed class NamePaintSpec {
    data class Solid(val color: Color) : NamePaintSpec()

    /**
     * A CSS linear gradient at [angle] degrees (0 points up, 90 right). [from] and
     * [to] are where the stops start and end, as fractions of the gradient line;
     * past them the stops repeat ([repeat]) or the end colours carry on.
     */
    data class Linear(
        val angle: Int,
        val colors: List<Color>,
        val stops: List<Float>,
        val from: Float,
        val to: Float,
        val repeat: Boolean,
    ) : NamePaintSpec()

    /** A radial gradient from the middle of the name; [reach] scales the radius for repeats. */
    data class Radial(
        val ellipse: Boolean,
        val colors: List<Color>,
        val stops: List<Float>,
        val reach: Float,
        val repeat: Boolean,
    ) : NamePaintSpec()
}

/**
 * Turns a paint into a [NamePaintSpec], or null when there is nothing to draw (the
 * name keeps its own Twitch colour). Gradients need two colours, so one stop is a
 * flat colour and none falls back to the paint's own colour. Image paints are
 * drawn in their own colour or first stop, never downloaded; the ones 7TV sends
 * today have neither, so they keep the Twitch colour.
 */
fun namePaintSpec(paint: SevenTvPaint): NamePaintSpec? {
    val sorted = paint.stops.sortedBy { it.at }
    val own = paint.argb?.let { Color(it) }
    if (paint.function == SevenTvPaintFunction.URL || sorted.size < 2) {
        val color = own ?: sorted.firstOrNull()?.let { Color(it.argb) } ?: return null
        return NamePaintSpec.Solid(color)
    }
    val colors = sorted.map { Color(it.argb) }
    val positions = sorted.map { it.at.coerceIn(0f, 1f) }
    val first = positions.first()
    val last = positions.last()
    // A repeat over a zero-length span would divide by zero: draw it flat instead.
    if (paint.repeat && last <= first) return NamePaintSpec.Solid(colors.first())
    return when (paint.function) {
        SevenTvPaintFunction.LINEAR_GRADIENT ->
            if (paint.repeat) {
                NamePaintSpec.Linear(
                    angle = paint.angle,
                    colors = colors,
                    stops = positions.map { (it - first) / (last - first) },
                    from = first,
                    to = last,
                    repeat = true,
                )
            } else {
                NamePaintSpec.Linear(paint.angle, colors, positions, from = 0f, to = 1f, repeat = false)
            }
        SevenTvPaintFunction.RADIAL_GRADIENT ->
            if (paint.repeat) {
                NamePaintSpec.Radial(paint.ellipse, colors, positions.map { it / last }, reach = last, repeat = true)
            } else {
                NamePaintSpec.Radial(paint.ellipse, colors, positions, reach = 1f, repeat = false)
            }
        SevenTvPaintFunction.URL -> null // handled above
    }
}

/** The WCAG contrast ratio (1 to 21) between two colours. */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** The smallest ratio a name paint may have against the chat background before it is lifted. */
const val MIN_NAME_PAINT_CONTRAST = 3f

/**
 * Keeps a paint readable on [background]: when the mean luminance of its colours
 * has a contrast ratio below [minRatio], every colour is blended the same small
 * amount towards white (dark background) or black (light background) until it
 * does. A paint that already reads fine comes back unchanged, and a lifted one
 * keeps its hues and its gradient shape.
 */
fun NamePaintSpec.withContrastOn(background: Color, minRatio: Float = MIN_NAME_PAINT_CONTRAST): NamePaintSpec {
    val colors = when (this) {
        is NamePaintSpec.Solid -> listOf(color)
        is NamePaintSpec.Linear -> colors
        is NamePaintSpec.Radial -> colors
    }
    fun meanContrast(blend: (Color) -> Color): Float {
        val mean = colors.map { blend(it).luminance() }.average().toFloat()
        val bg = background.luminance()
        return (maxOf(mean, bg) + 0.05f) / (minOf(mean, bg) + 0.05f)
    }
    if (meanContrast { it } >= minRatio) return this
    val target = if (background.luminance() < 0.18f) Color.White else Color.Black
    // Blending all the way reaches the best contrast there is; if even that falls
    // short (a mid-grey background) take it anyway.
    var low = 0f
    var high = 1f
    repeat(12) {
        val mid = (low + high) / 2f
        if (meanContrast { lerp(it, target, mid) } >= minRatio) high = mid else low = mid
    }
    val lift = { color: Color -> lerp(color, target, high) }
    return when (this) {
        is NamePaintSpec.Solid -> copy(color = lift(color))
        is NamePaintSpec.Linear -> copy(colors = colors.map(lift))
        is NamePaintSpec.Radial -> copy(colors = colors.map(lift))
    }
}

/**
 * The CSS gradient line for [angleDegrees] across a [size] box: centred, pointing
 * along the angle (0 up, 90 right, clockwise), and long enough that its ends touch
 * the box's far corners.
 */
fun linearGradientLine(angleDegrees: Int, size: Size): Pair<Offset, Offset> {
    val radians = angleDegrees * PI / 180.0
    val dx = sin(radians).toFloat()
    val dy = -cos(radians).toFloat()
    val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    return Offset(center.x - dx * half, center.y - dy * half) to Offset(center.x + dx * half, center.y + dy * half)
}

/**
 * The radius a CSS `radial-gradient` reaches for a [size] box (farthest corner). An
 * ellipse is drawn as the circle matching its horizontal radius, which is what
 * shows on a one-line name.
 */
fun radialGradientRadius(ellipse: Boolean, size: Size): Float {
    val radius = if (ellipse) size.width / 2f * sqrt(2f) else hypot(size.width / 2f, size.height / 2f)
    return radius.coerceAtLeast(1f)
}

/** A gradient brush that fits itself to the name it paints. */
private class LinearPaintBrush(private val spec: NamePaintSpec.Linear) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val (start, end) = linearGradientLine(spec.angle, size)
        val delta = end - start
        return LinearGradientShader(
            from = start + delta * spec.from,
            to = start + delta * spec.to,
            colors = spec.colors,
            colorStops = spec.stops,
            tileMode = if (spec.repeat) TileMode.Repeated else TileMode.Clamp,
        )
    }
}

private class RadialPaintBrush(private val spec: NamePaintSpec.Radial) : ShaderBrush() {
    override fun createShader(size: Size): Shader = RadialGradientShader(
        center = Offset(size.width / 2f, size.height / 2f),
        radius = (radialGradientRadius(spec.ellipse, size) * spec.reach).coerceAtLeast(1f),
        colors = spec.colors,
        colorStops = spec.stops,
        tileMode = if (spec.repeat) TileMode.Repeated else TileMode.Clamp,
    )
}

/**
 * Name paint specs, worked out once per paint id and shared by every chat row.
 * Bounded like the paint store in core. Read during composition only.
 */
object NamePaintBrushes {
    private const val MAX_PAINTS = 512

    private class Entry(val paint: SevenTvPaint, val spec: NamePaintSpec?)

    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > MAX_PAINTS
    }

    fun specFor(paint: SevenTvPaint): NamePaintSpec? = synchronized(cache) {
        val cached = cache[paint.id]
        // A paint redefined under the same id is worked out again.
        if (cached != null && cached.paint == paint) return cached.spec
        namePaintSpec(paint).also { cache[paint.id] = Entry(paint, it) }
    }

    /**
     * A brush for one row's name. Each row gets its own small brush (it remembers
     * the shader for its own size), built once when the row or its paint changes,
     * never per frame.
     */
    fun brushFor(paint: SevenTvPaint, background: Color = Color.Unspecified): Brush? {
        val raw = specFor(paint) ?: return null
        val spec = if (background == Color.Unspecified) raw else raw.withContrastOn(background)
        return when (spec) {
            is NamePaintSpec.Solid -> SolidColor(spec.color)
            is NamePaintSpec.Linear -> LinearPaintBrush(spec)
            is NamePaintSpec.Radial -> RadialPaintBrush(spec)
        }
    }
}

/**
 * The brush for [paint] on this row, or null to keep the Twitch name colour. Pass
 * the colour the name sits on so a paint too close to it is lifted to stay readable.
 */
@Composable
fun rememberNamePaintBrush(paint: SevenTvPaint?, background: Color = Color.Unspecified): Brush? =
    remember(paint, background) { paint?.let { NamePaintBrushes.brushFor(it, background) } }
