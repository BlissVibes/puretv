package com.puretv.twitch.desktop.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.DialogWindow
import androidx.compose.foundation.layout.wrapContentHeight
import com.puretv.twitch.desktop.platform.HostOs
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.puretv.twitch.desktop.ui.theme.PureTvMotion
import com.puretv.twitch.desktop.ui.theme.PureTvTheme
import java.awt.Color
import java.awt.Dialog
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.geom.Area
import javax.swing.RootPaneContainer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The player's bars, floated over the video in a window of their own.
 *
 * The video is a native surface that paints over everything Compose draws in the main
 * window, so the bars used to sit above and below it in the layout. Showing or hiding them
 * then resized the video, which made the picture jump and flash. Here the video keeps its
 * size: the bars live in one owned, borderless window laid exactly over the video's slot,
 * the same idea as the glass over the mini player's video.
 *
 * Hiding and showing is an unroll, not a slide: the window's shape is a strip anchored to
 * the top edge that grows downward ([top]) and one anchored to the bottom edge that grows
 * upward ([bottom]), while the bars themselves stay where they are. With the bars rolled
 * up the window is hidden, so the video underneath takes the mouse again.
 *
 * The window never takes keyboard focus, so the player's shortcuts keep working while the
 * pointer is on a bar. Composition locals and state carry over from the caller.
 *
 * @param owner the main window; the bars stay above it and follow it.
 * @param slot the video's slot in the owner's content, in pixels. Null takes the bars away
 *   (mini player, no video).
 * @param shown true while the bars are unrolled.
 */
@Composable
fun FloatingPlayerChrome(
    owner: Window?,
    slot: Rect?,
    shown: Boolean,
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
) {
    if (owner == null) return
    if (HostOs.current == HostOs.MACOS) {
        InWindowChrome(slot, shown, top, bottom)
        return
    }
    val c = PureTvTheme.colors
    val density = LocalDensity.current.density
    val progress by animateFloatAsState(
        targetValue = if (shown && slot != null) 1f else 0f,
        animationSpec = if (shown) {
            PureTvMotion.move(PureTvMotion.Medium, PureTvMotion.Decelerate)
        } else {
            PureTvMotion.move(PureTvMotion.Fast + 30, PureTvMotion.Accelerate)
        },
        label = "playerChromeUnroll",
    )
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    // Bumped when the owner moves or resizes: the bars' window is placed in screen units.
    var ownerMoves by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<ComposeDialog?>(null) }
    val latestSlot by rememberUpdatedState(slot)

    DisposableEffect(owner) {
        val follow = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) { ownerMoves++ }
            override fun componentResized(e: ComponentEvent) { ownerMoves++ }
        }
        owner.addComponentListener(follow)
        onDispose { owner.removeComponentListener(follow) }
    }

    // Place the window over the slot, and cut its shape to the unrolled strips.
    LaunchedEffect(dialog, owner, density) {
        val d = dialog ?: return@LaunchedEffect
        snapshotFlow { ChromeFrame(latestSlot, progress, topPx, bottomPx, ownerMoves) }.collect { f ->
            val s = f.slot ?: return@collect
            val origin = runCatching { contentOrigin(owner) }.getOrNull() ?: return@collect
            val b = FloatingChromeGeometry.windowBounds(s.left, s.top, s.right, s.bottom, density, origin.x, origin.y)
            if (d.x != b.x || d.y != b.y || d.width != b.width || d.height != b.height) {
                d.setBounds(b.x, b.y, b.width, b.height)
            }
            val strips = FloatingChromeGeometry.strips(
                slotWidth = ceil(s.width / density).toInt(),
                slotHeight = ceil(s.height / density).toInt(),
                top = ceil(f.topPx / density).toInt(),
                bottom = ceil(f.bottomPx / density).toInt(),
                progress = f.progress,
            )
            val shape = Area()
            strips.forEach { shape.add(Area(Rectangle(it.x, it.y, it.width, it.height))) }
            // An empty shape means "no shape" to AWT, which would show the whole window, so
            // rolled up it keeps one corner unit; the window itself is hidden then (below).
            if (shape.isEmpty) shape.add(Area(Rectangle(0, 0, 1, 1)))
            // A system without shaped windows shows the bars whole: no unroll, same result.
            runCatching { d.shape = shape }
        }
    }

    DialogWindow(
        // Rolled up, the window is hidden, so the video underneath takes the mouse again.
        visible = slot != null && (shown || progress > 0f),
        create = {
            ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
                isUndecorated = true
                isResizable = false
                // Never the focused window: the player's keys go on reaching the main window.
                focusableWindowState = false
                background = Color.BLACK
                // Nothing but a corner shows until the first strips are cut.
                runCatching { shape = Area(Rectangle(0, 0, 1, 1)) }
                dialog = this
            }
        },
        dispose = {
            dialog = null
            it.dispose()
        },
    ) {
        val innerDensity = LocalDensity.current
        val s = slot
        // The window is a little larger than the slot (see FloatingChromeGeometry); its shape
        // never shows that margin, and it is the bars' colour in case a rounded edge does.
        Box(Modifier.fillMaxSize().background(c.surfaceContainer)) {
            if (s != null) {
                val w = with(innerDensity) { s.width.toDp() }
                val h = with(innerDensity) { s.height.toDp() }
                Box(Modifier.size(w, h)) {
                    Box(Modifier.align(Alignment.TopStart).width(w).onSizeChanged { topPx = it.height }) { top() }
                    Box(Modifier.align(Alignment.BottomStart).width(w).onSizeChanged { bottomPx = it.height }) { bottom() }
                }
            }
        }
    }
}

/**
 * The macOS way to the same unroll. There the video is drawn by Compose (see
 * ComposeVideoCanvas), so the bars lie over it in the same window: each one is clipped to
 * its unrolled part, the top bar from the video's top edge down, the controls from its
 * bottom edge up. No window of its own: on macOS those flashed white and stuttered while
 * resizing, and ignored window shapes. The clip is also where clicks stop, so the video
 * underneath takes the mouse wherever no bar shows.
 */
@Composable
private fun InWindowChrome(
    slot: Rect?,
    shown: Boolean,
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = if (shown && slot != null) 1f else 0f,
        animationSpec = if (shown) {
            PureTvMotion.move(PureTvMotion.Medium, PureTvMotion.Decelerate)
        } else {
            PureTvMotion.move(PureTvMotion.Fast + 30, PureTvMotion.Accelerate)
        },
        label = "playerChromeUnroll",
    )
    var origin by remember { mutableStateOf(Offset.Zero) }
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().zIndex(1f).onGloballyPositioned { origin = it.positionInRoot() }) {
        if (slot == null || progress <= 0f) return@Box
        val w = with(density) { slot.width.toDp() }
        val h = with(density) { slot.height.toDp() }
        Box(
            Modifier
                .offset { IntOffset((slot.left - origin.x).roundToInt(), (slot.top - origin.y).roundToInt()) }
                .size(w, h),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .width(w)
                    .height(with(density) { (topPx * progress).toDp() })
                    .clipToBounds(),
            ) {
                Box(
                    Modifier
                        .wrapContentHeight(Alignment.Top, unbounded = true)
                        // Bounded by the video, as in the window version: the playback menu
                        // takes the height the controls leave, which is nothing when unbounded.
                        .heightIn(max = h)
                        .width(w)
                        .onSizeChanged { topPx = it.height },
                ) { top() }
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .width(w)
                    .height(with(density) { (bottomPx * progress).toDp() })
                    .clipToBounds(),
            ) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .wrapContentHeight(Alignment.Bottom, unbounded = true)
                        // Bounded by the video, as in the window version: the playback menu
                        // takes the height the controls leave, which is nothing when unbounded.
                        .heightIn(max = h)
                        .width(w)
                        .onSizeChanged { bottomPx = it.height },
                ) { bottom() }
            }
        }
    }
}

private data class ChromeFrame(val slot: Rect?, val progress: Float, val topPx: Int, val bottomPx: Int, val ownerMoves: Int)

/** The top-left of the owner's content on screen, in screen units. */
private fun contentOrigin(owner: Window): Point =
    ((owner as? RootPaneContainer)?.contentPane ?: owner).locationOnScreen

/** Where the bars' window sits and which strips of it show. Pure, so it is unit tested. */
object FloatingChromeGeometry {
    data class Box(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * The window for a slot given in pixels of the owner's content, whose top-left is at
     * ([originX], [originY]) on screen. Sizes are in screen units.
     *
     * The size is rounded up to a run of units that is a whole number of pixels at this
     * display scale (4 units at 125% and 175%, 2 at 150%). A window that is a fraction of a
     * pixel wide makes Skiko stretch its picture, which blurs it and moves it by a pixel
     * or two. The extra is never shown: [strips] stay inside the slot.
     */
    fun windowBounds(
        slotLeft: Float,
        slotTop: Float,
        slotRight: Float,
        slotBottom: Float,
        density: Float,
        originX: Int,
        originY: Int,
    ): Box {
        val left = floor(slotLeft / density).toInt()
        val top = floor(slotTop / density).toInt()
        val right = ceil(slotRight / density).toInt()
        val bottom = ceil(slotBottom / density).toInt()
        val run = wholePixelUnits(density)
        return Box(
            x = originX + left,
            y = originY + top,
            width = roundUp((right - left).coerceAtLeast(1), run),
            height = roundUp((bottom - top).coerceAtLeast(1), run),
        )
    }

    /**
     * The strips of the window that show, in units from its top-left: [top] units unrolled
     * down from the top edge and [bottom] units unrolled up from the bottom edge of the
     * slot, each by [progress] (0 hidden, 1 whole). Nothing at 0, so the video takes the mouse.
     */
    fun strips(slotWidth: Int, slotHeight: Int, top: Int, bottom: Int, progress: Float): List<Box> {
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0f || slotWidth <= 0 || slotHeight <= 0) return emptyList()
        val out = ArrayList<Box>(2)
        val topShown = (top.coerceIn(0, slotHeight) * p).roundToInt()
        if (topShown > 0) out += Box(0, 0, slotWidth, topShown)
        val bottomShown = (bottom.coerceIn(0, slotHeight) * p).roundToInt()
        if (bottomShown > 0) out += Box(0, slotHeight - bottomShown, slotWidth, bottomShown)
        return out
    }

    /** Units in the smallest run that is a whole number of pixels: 1 at 100% and 200%, 2 at 150%, 4 at 125% and 175%. */
    fun wholePixelUnits(density: Float): Int {
        if (density.isNaN() || density <= 0f) return 1
        for (units in 1..4) {
            val px = units * density
            if (kotlin.math.abs(px - Math.rint(px.toDouble()).toFloat()) < 1e-4f) return units
        }
        return 1
    }

    private fun roundUp(value: Int, step: Int): Int = if (step <= 1) value else (value + step - 1) / step * step
}

