package com.puretv.twitch.desktop.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import com.puretv.twitch.core.emotes.SevenTvPaint
import com.puretv.twitch.core.emotes.SevenTvPaintFunction
import com.puretv.twitch.core.emotes.SevenTvPaintStop
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NamePaintBrushesTest {

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val green = 0xFF00FF00.toInt()

    private fun paint(
        function: SevenTvPaintFunction = SevenTvPaintFunction.LINEAR_GRADIENT,
        stops: List<SevenTvPaintStop> = emptyList(),
        argb: Int? = null,
        repeat: Boolean = false,
        angle: Int = 90,
        id: String = "p",
    ) = SevenTvPaint(id, "Test", function, angle = angle, repeat = repeat, argb = argb, stops = stops)

    private fun assertNear(expected: Offset, actual: Offset) {
        assertTrue(abs(expected.x - actual.x) < 0.01f && abs(expected.y - actual.y) < 0.01f, "expected $expected, was $actual")
    }

    // ---- Gradient line ----

    private val box = Size(100f, 20f)

    @Test fun angleZeroRunsBottomToTop() {
        val (start, end) = linearGradientLine(0, box)
        assertNear(Offset(50f, 20f), start)
        assertNear(Offset(50f, 0f), end)
    }

    @Test fun angleNinetyRunsLeftToRight() {
        val (start, end) = linearGradientLine(90, box)
        assertNear(Offset(0f, 10f), start)
        assertNear(Offset(100f, 10f), end)
    }

    @Test fun angle180RunsTopToBottom() {
        val (start, end) = linearGradientLine(180, box)
        assertNear(Offset(50f, 0f), start)
        assertNear(Offset(50f, 20f), end)
    }

    @Test fun angle360IsTheSameAsZero() {
        val (s0, e0) = linearGradientLine(0, box)
        val (s360, e360) = linearGradientLine(360, box)
        assertNear(s0, s360)
        assertNear(e0, e360)
    }

    @Test fun diagonalLineReachesTheFarCorners() {
        // CSS: the line length is |w sin a| + |h cos a|, centred on the box.
        val (start, end) = linearGradientLine(45, box)
        val length = (end - start).getDistance()
        assertTrue(abs(length - (100f + 20f) * sqrt(0.5f)) < 0.01f, "length was $length")
        assertNear(Offset(50f, 10f), (start + end) / 2f)
    }

    // ---- Specs ----

    @Test fun noStopsAndNoColourDrawsNothing() {
        assertNull(namePaintSpec(paint()))
    }

    @Test fun imagePaintWithNoColourKeepsTheNameColour() {
        assertNull(namePaintSpec(paint(SevenTvPaintFunction.URL)))
    }

    @Test fun imagePaintUsesItsOwnColourThenFirstStop() {
        assertEquals(NamePaintSpec.Solid(Color(red)), namePaintSpec(paint(SevenTvPaintFunction.URL, argb = red)))
        val stops = listOf(SevenTvPaintStop(0.5f, blue), SevenTvPaintStop(0f, green))
        assertEquals(NamePaintSpec.Solid(Color(green)), namePaintSpec(paint(SevenTvPaintFunction.URL, stops = stops)))
    }

    @Test fun oneStopIsAFlatColour() {
        // A gradient with one colour would throw when drawn.
        assertEquals(NamePaintSpec.Solid(Color(blue)), namePaintSpec(paint(stops = listOf(SevenTvPaintStop(0.3f, blue)))))
    }

    @Test fun stopsAreSortedAndClamped() {
        val spec = namePaintSpec(
            paint(stops = listOf(SevenTvPaintStop(1.4f, blue), SevenTvPaintStop(-0.2f, red), SevenTvPaintStop(0.5f, green))),
        )
        val linear = assertIs<NamePaintSpec.Linear>(spec)
        assertEquals(listOf(0f, 0.5f, 1f), linear.stops)
        assertEquals(listOf(Color(red), Color(green), Color(blue)), linear.colors)
        assertEquals(0f, linear.from)
        assertEquals(1f, linear.to)
    }

    @Test fun repeatingLinearRescalesStopsToTheirSpan() {
        val spec = namePaintSpec(
            paint(repeat = true, stops = listOf(SevenTvPaintStop(0.1f, red), SevenTvPaintStop(0.2f, blue), SevenTvPaintStop(0.3f, red))),
        )
        val linear = assertIs<NamePaintSpec.Linear>(spec)
        assertTrue(linear.repeat)
        assertEquals(0.1f, linear.from)
        assertEquals(0.3f, linear.to)
        assertEquals(0f, linear.stops.first())
        assertTrue(abs(linear.stops[1] - 0.5f) < 0.001f)
        assertEquals(1f, linear.stops.last())
    }

    @Test fun repeatOverAZeroSpanIsFlat() {
        val spec = namePaintSpec(paint(repeat = true, stops = listOf(SevenTvPaintStop(0.4f, red), SevenTvPaintStop(0.4f, blue))))
        assertEquals(NamePaintSpec.Solid(Color(red)), spec)
    }

    @Test fun radialKeepsItsShape() {
        val p = paint(SevenTvPaintFunction.RADIAL_GRADIENT, stops = listOf(SevenTvPaintStop(0f, red), SevenTvPaintStop(1f, blue)))
            .copy(ellipse = true)
        val radial = assertIs<NamePaintSpec.Radial>(namePaintSpec(p))
        assertTrue(radial.ellipse)
        assertEquals(1f, radial.reach)
    }

    @Test fun radialRadiusReachesTheFarthestCorner() {
        assertTrue(abs(radialGradientRadius(ellipse = false, Size(60f, 80f)) - 50f) < 0.01f)
        assertTrue(abs(radialGradientRadius(ellipse = true, Size(100f, 20f)) - 50f * sqrt(2f)) < 0.01f)
        assertEquals(1f, radialGradientRadius(ellipse = false, Size.Zero))
    }

    // ---- Brushes and cache ----

    @Test fun brushKindsFollowTheSpec() {
        assertNull(NamePaintBrushes.brushFor(paint(id = "none")))
        assertIs<SolidColor>(NamePaintBrushes.brushFor(paint(id = "solid", argb = red, function = SevenTvPaintFunction.URL)))
        val gradient = paint(id = "grad", stops = listOf(SevenTvPaintStop(0f, red), SevenTvPaintStop(1f, blue)))
        assertIs<ShaderBrush>(NamePaintBrushes.brushFor(gradient))
        val radial = gradient.copy(id = "rad", function = SevenTvPaintFunction.RADIAL_GRADIENT)
        assertIs<ShaderBrush>(NamePaintBrushes.brushFor(radial))
    }

    @Test fun gradientBrushesBuildShadersForAnyNameSize() {
        val stops = listOf(SevenTvPaintStop(0.1f, red), SevenTvPaintStop(0.2f, blue), SevenTvPaintStop(0.3f, green))
        val paints = listOf(
            paint(id = "s1", stops = stops),
            paint(id = "s2", stops = stops, repeat = true, angle = 236),
            paint(id = "s3", stops = stops, function = SevenTvPaintFunction.RADIAL_GRADIENT),
            paint(id = "s4", stops = stops, function = SevenTvPaintFunction.RADIAL_GRADIENT, repeat = true),
        )
        for (p in paints) {
            val brush = assertIs<ShaderBrush>(NamePaintBrushes.brushFor(p))
            listOf(Size(120f, 18f), Size(1f, 1f), Size.Zero).forEach { brush.createShader(it) }
        }
    }

    @Test fun specIsWorkedOutOncePerPaint() {
        val p = paint(id = "cached", stops = listOf(SevenTvPaintStop(0f, red), SevenTvPaintStop(1f, blue)))
        assertSame(NamePaintBrushes.specFor(p), NamePaintBrushes.specFor(p.copy()))
    }

    @Test fun redefinedPaintIsWorkedOutAgain() {
        val p = paint(id = "redefined", stops = listOf(SevenTvPaintStop(0f, red), SevenTvPaintStop(1f, blue)))
        NamePaintBrushes.specFor(p)
        val changed = p.copy(stops = listOf(SevenTvPaintStop(0f, green), SevenTvPaintStop(1f, blue)))
        val spec = assertIs<NamePaintSpec.Linear>(NamePaintBrushes.specFor(changed))
        assertEquals(Color(green), spec.colors.first())
    }
}
