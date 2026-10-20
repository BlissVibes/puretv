package com.puretv.twitch.desktop.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.puretv.twitch.core.emotes.SevenTvPaint
import com.puretv.twitch.core.emotes.SevenTvPaintFunction
import com.puretv.twitch.core.emotes.SevenTvPaintStop
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders a painted name offscreen the way a chat row does (a span brush inside
 * Material3 Text that also has a colour) and reads the pixels back, so the
 * gradient is proven to reach the glyphs and to win over the name colour.
 */
class NamePaintRenderTest {

    @Test fun spanBrushPaintsTheNameAndWinsOverItsColour() {
        val paint = SevenTvPaint(
            id = "render-test",
            name = "Red to blue",
            function = SevenTvPaintFunction.LINEAR_GRADIENT,
            angle = 90,
            stops = listOf(SevenTvPaintStop(0f, 0xFFFF0000.toInt()), SevenTvPaintStop(1f, 0xFF0000FF.toInt())),
        )
        val width = 600
        val height = 80
        val image = renderComposeScene(width, height) {
            val brush = rememberNamePaintBrush(paint)!!
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Text(
                    buildAnnotatedString { withStyle(SpanStyle(brush = brush)) { append("MMMMMMMMMMMM") } },
                    color = Color.Green,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        val bitmap = Bitmap.makeFromImage(image)
        var leftRed = 0L
        var leftBlue = 0L
        var rightRed = 0L
        var rightBlue = 0L
        var greenWins = 0
        var lit = 0
        var textRight = 0
        for (x in 0 until width) for (y in 0 until height) {
            val c = bitmap.getColor(x, y)
            if ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF) > 120) textRight = maxOf(textRight, x)
        }
        assertTrue(textRight > 0, "nothing was drawn")
        for (x in 0..textRight) for (y in 0 until height) {
            val c = bitmap.getColor(x, y)
            val r = c shr 16 and 0xFF
            val g = c shr 8 and 0xFF
            val b = c and 0xFF
            if (r + g + b <= 120) continue
            lit++
            if (g > r && g > b) greenWins++
            if (x < textRight / 3) { leftRed += r; leftBlue += b }
            if (x > textRight * 2 / 3) { rightRed += r; rightBlue += b }
        }
        assertTrue(lit > 100, "too few glyph pixels: $lit")
        assertTrue(greenWins == 0, "the name colour showed through on $greenWins pixels")
        assertTrue(leftRed > leftBlue, "left third should be red: r=$leftRed b=$leftBlue")
        assertTrue(rightBlue > rightRed, "right third should be blue: r=$rightRed b=$rightBlue")
    }
}
