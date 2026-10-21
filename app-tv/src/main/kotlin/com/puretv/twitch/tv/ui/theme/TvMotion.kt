package com.puretv.twitch.tv.ui.theme

import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.puretv.twitch.core.motion.MotionTokens

/**
 * The TV app's one motion system. Same vocabulary and numbers as the phone's
 * `PureTvMotion` (both read [MotionTokens]), tuned for a remote:
 *
 *  - Focus is the interaction. It answers inside ~150 ms with one spring, so a
 *    viewer holding the D-pad down a shelf sees each card lift and settle without
 *    the previous one still being on its way back.
 *  - Nothing here loops, blurs or animates layout in a lazy list: a Fire TV stick
 *    has one slow core and shares it with the video decoder.
 *  - Screens change with a short fade. The slide used on the phone is wasted on a
 *    ten-foot UI where focus, not the page, is what the eye follows.
 */
object PureTvTvMotion {
    // ---- Durations (ms) ----
    const val Fast = MotionTokens.FastMs
    const val Medium = MotionTokens.MediumMs
    const val Slow = MotionTokens.SlowMs
    const val Exit = MotionTokens.ExitMs

    // ---- Easings ----
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    // ---- Focus ----

    /** How far a focused card grows. Small enough not to overlap its neighbours. */
    const val FocusScale = MotionTokens.TvFocusScale

    /**
     * The focus spring. One progress value (0 unfocused, 1 focused) drives scale,
     * corner radius, fill and ring together. Damping 0.8 gives a single small
     * overshoot; stiffness 1000 settles in about 160 ms.
     */
    val FocusSpring: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 1000f)

    /** Kept for the one screen that still animates a radius by hand. */
    val MorphSpring: SpringSpec<Dp> = spring(dampingRatio = 0.7f, stiffness = 700f)
    val MorphSpringFloat: SpringSpec<Float> = spring(dampingRatio = 0.7f, stiffness = 700f)

    /** Peak alpha of the soft halo drawn just outside a focused surface's ring. */
    const val HaloAlpha = 0.28f

    // ---- Navigation ----
    // Navigation-compose's TV default is a 700 ms fade; these replace it.
    fun screenEnter(reduced: Boolean): EnterTransition =
        fadeIn(tween(if (reduced) 0 else Medium, delayMillis = if (reduced) 0 else 60, easing = Standard))

    fun screenExit(reduced: Boolean): ExitTransition = fadeOut(tween(if (reduced) 0 else Fast))

    // ---- Chrome ----

    val ControlsEnter: EnterTransition = fadeIn(tween(Fast, easing = Standard))
    val ControlsExit: ExitTransition = fadeOut(tween(Exit, easing = Accelerate))

    /** The player's header bar: drops in from the top edge. */
    val HeaderEnter: EnterTransition =
        fadeIn(tween(Medium, easing = Standard)) + slideInVertically(tween(Medium, easing = Standard)) { -it / 4 }
    val HeaderExit: ExitTransition =
        fadeOut(tween(Exit, easing = Accelerate)) + slideOutVertically(tween(Exit, easing = Accelerate)) { -it / 4 }

    /** The player's footer bar and bottom banners: rise from the bottom edge. */
    val FooterEnter: EnterTransition =
        fadeIn(tween(Medium, easing = Standard)) + slideInVertically(tween(Medium, easing = Standard)) { it / 4 }
    val FooterExit: ExitTransition =
        fadeOut(tween(Exit, easing = Accelerate)) + slideOutVertically(tween(Exit, easing = Accelerate)) { it / 4 }

    /** Toasts and banners over the picture. */
    val BannerEnter: EnterTransition = FooterEnter
    val BannerExit: ExitTransition = FooterExit

    /** One state giving way to another: skeleton to content, empty to filled. */
    val SwapEnter: EnterTransition = fadeIn(tween(Medium, easing = Standard))
    val SwapExit: ExitTransition = fadeOut(tween(Fast))

    // ---- Focus scrolling ----

    /** Scroll animation for focus-driven scrolls: a short tween, so a held D-pad never queues springs. */
    val ScrollSpec: AnimationSpec<Float> = tween(Medium, easing = Standard)

    /**
     * A shelf follows focus horizontally: the focused card is kept centred, except
     * at the ends where the list cannot scroll further. Each step is one card wide,
     * so the movement is constant and never jumps.
     */
    @OptIn(ExperimentalFoundationApi::class)
    val ShelfScroll: BringIntoViewSpec = object : BringIntoViewSpec {
        override val scrollAnimationSpec: AnimationSpec<Float> = ScrollSpec
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            MotionTokens.centredScrollDistance(offset, size, containerSize)
    }

    /**
     * A page follows focus vertically with a dead band: while the focused row is
     * comfortably inside the screen the page stays still, and once it nears an edge
     * the page glides to centre it. Rows taller than the screen (the hero) keep the
     * default of aligning their leading edge.
     */
    @OptIn(ExperimentalFoundationApi::class)
    val PageScroll: BringIntoViewSpec = object : BringIntoViewSpec {
        override val scrollAnimationSpec: AnimationSpec<Float> = ScrollSpec
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            MotionTokens.bandedScrollDistance(offset, size, containerSize, PageBandFraction)
    }

    private const val PageBandFraction = 0.18f
}

/**
 * Wraps a horizontal shelf so focus moves keep the focused card centred. The app
 * root provides [PureTvTvMotion.PageScroll] for everything else (vertical pages
 * and grids), so only shelves need this.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvShelfScroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides PureTvTvMotion.ShelfScroll, content = content)
}

/** True when the viewer turned animations off. Provided once by [PureTvTvTheme]. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Reads the system animator duration scale and follows it live. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember { mutableStateOf(MotionTokens.isReducedMotion(animatorDurationScale(context))) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = MotionTokens.isReducedMotion(animatorDurationScale(context))
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return reduced
}

/** The system animator scale, 1 when unreadable. Shared with the image loader. */
fun animatorDurationScale(context: Context): Float =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
        .getOrDefault(1f)
