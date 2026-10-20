package com.puretv.twitch.android.ui.components

import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.puretv.twitch.core.stream.Storyboard
import com.puretv.twitch.core.stream.StoryboardParser

/**
 * Sprite sheets decoded so far. A sheet holds about a hundred tiles, so a drag
 * along the bar re-uses one or two of them; four is enough to scrub back and
 * forth without decoding again.
 */
private val spriteCache = LruCache<String, ImageBitmap>(4)

/**
 * The frame Twitch's storyboard has for [positionMs], cropped out of its sprite
 * sheet. Draws nothing until the sheet arrives. The "low" tiles are small and
 * enough for a thumbnail the size of a thumb.
 */
@Composable
fun StoryboardPreview(storyboard: Storyboard, positionMs: Long, modifier: Modifier = Modifier) {
    val spec = remember(storyboard) { storyboard.spec("low") } ?: return
    val tile = StoryboardParser.tileAt(spec, storyboard.baseUrl, positionMs / 1000)
    val context = LocalContext.current
    val sheet by produceState<ImageBitmap?>(spriteCache.get(tile.imageUrl), tile.imageUrl) {
        value = spriteCache.get(tile.imageUrl) ?: runCatching {
            // Software bitmap: a hardware one cannot be cropped by drawImage on every device.
            val request = ImageRequest.Builder(context).data(tile.imageUrl).allowHardware(false).build()
            val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
            result?.image?.toBitmap()?.asImageBitmap()
        }.getOrNull()?.also { spriteCache.put(tile.imageUrl, it) }
    }
    val density = LocalDensity.current
    // Draw at the tile's own pixel size, scaled to the screen so it stays sharp.
    val width = with(density) { (tile.tileWidthPx.coerceAtLeast(1) * 1.2f).toDp() }
    val height = with(density) { (tile.tileHeightPx.coerceAtLeast(1) * 1.2f).toDp() }
    Canvas(modifier.size(width, height).clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
        sheet?.let {
            drawImage(
                image = it,
                srcOffset = IntOffset(tile.srcXPx, tile.srcYPx),
                srcSize = IntSize(tile.tileWidthPx, tile.tileHeightPx),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            )
        }
    }
}
