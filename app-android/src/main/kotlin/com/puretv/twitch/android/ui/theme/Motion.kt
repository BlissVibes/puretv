package com.puretv.twitch.android.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.puretv.twitch.core.motion.MotionTokens

/**
 * The phone's one motion system. Every duration, easing, spring and enter/exit
 * pair the app animates with lives here, so a tuning change is one edit.
 *
 * Principles:
 *  - Causal: a press answers under the finger, a push slides in from the side it
 *    came from, a pop reverses it.
 *  - Short: nothing a viewer waits on is longer than [Medium]; exits are faster
 *    than entrances.
 *  - Cheap: springs and fades run in graphics layers, never in layout, and nothing
 *    here loops while video plays.
 *  - Optional: with the system "remove animations" setting on, [LocalReducedMotion]
 *    is true and the transition builders collapse to a plain fade.
 */
object PureTvMotion {
    // ---- Durations (ms) ----
    const val Fast = MotionTokens.FastMs
    const val Medium = MotionTokens.MediumMs
    const val Slow = MotionTokens.SlowMs
    const val Exit = MotionTokens.ExitMs

    // ---- Easings ----

    /** Emphasized decelerate: quick start, long soft landing. The default for anything entering. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** For things leaving: starts slowly, then goes. */
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    // ---- Springs ----

    /** Corner morph. A little overshoot reads as elastic; more than this reads as wobble. */
    val MorphSpring: SpringSpec<Dp> = spring(dampingRatio = 0.65f, stiffness = 500f)

    /** Press scale. Lands in about 200 ms with a barely visible overshoot on release. */
    val MorphSpringFloat: SpringSpec<Float> = spring(dampingRatio = 0.7f, stiffness = 700f)

    /** Toggle pop (follow, bell, like): a deliberate, single bounce. */
    val PopSpring: SpringSpec<Float> = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

    /** List placement when items reorder or are removed. Critically damped so rows never bounce. */
    val PlacementSpring: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)

    /** A container that grows or shrinks as rows come and go (Following live and offline lists). */
    val ContentSizeSpring: FiniteAnimationSpec<IntSize> =
        spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold)

    // ---- Navigation ----

    /** A tab swapped for another: the old one fades out, then the new one fades in. No travel. */
    fun tabEnter(reduced: Boolean): EnterTransition =
        fadeIn(tween(if (reduced) 0 else Medium - 70, delayMillis = if (reduced) 0 else 70, easing = Standard))

    fun tabExit(reduced: Boolean): ExitTransition = fadeOut(tween(if (reduced) 0 else 70))

    /** A detail screen opening: eases in from the right by a tenth of the screen while it fades up. */
    fun pushEnter(reduced: Boolean): EnterTransition =
        if (reduced) fadeIn(tween(0))
        else fadeIn(tween(Medium, easing = Standard)) + slideInHorizontally(tween(Medium, easing = Standard)) { it / 10 }

    /** The screen underneath a push drifts left a little and fades, so the pair reads as one move. */
    fun pushExit(reduced: Boolean): ExitTransition =
        if (reduced) fadeOut(tween(0))
        else fadeOut(tween(Exit, easing = Accelerate)) + slideOutHorizontally(tween(Exit, easing = Accelerate)) { -it / 14 }

    /** Back: the screen underneath returns from the left. */
    fun popEnter(reduced: Boolean): EnterTransition =
        if (reduced) fadeIn(tween(0))
        else fadeIn(tween(Medium, easing = Standard)) + slideInHorizontally(tween(Medium, easing = Standard)) { -it / 14 }

    /** Back: the departing screen leaves right, faster than it arrived. */
    fun popExit(reduced: Boolean): ExitTransition =
        if (reduced) fadeOut(tween(0))
        else fadeOut(tween(Exit, easing = Accelerate)) + slideOutHorizontally(tween(Exit, easing = Accelerate)) { it / 10 }

    /**
     * Routes that host the video surface. They fade only: sliding or scaling a
     * SurfaceView makes it tear, and the surface itself must never be recreated
     * by a transition.
     */
    fun playerEnter(reduced: Boolean): EnterTransition = fadeIn(tween(if (reduced) 0 else Fast))

    /**
     * Leaving the player: a short fade. It cannot be None, because the departing
     * screen keeps the top of the stack while the one beneath fades in and would
     * hide it for the whole transition before vanishing.
     */
    fun playerExit(): ExitTransition = fadeOut(tween(Fast))

    // ---- Chrome ----

    /** Player controls and overlays: one pair so every control group fades together. */
    val ControlsEnter: EnterTransition = fadeIn(tween(Fast, easing = Standard))
    val ControlsExit: ExitTransition = fadeOut(tween(Exit, easing = Accelerate))

    /** Banners and toasts: rise from the edge they are anchored to. */
    val BannerEnter: EnterTransition =
        fadeIn(tween(Medium, easing = Standard)) + slideInVertically(tween(Medium, easing = Standard)) { it / 3 }
    val BannerExit: ExitTransition =
        fadeOut(tween(Exit, easing = Accelerate)) + slideOutVertically(tween(Exit, easing = Accelerate)) { it / 3 }

    /** Dialogs and menus: a small scale-in from just under full size. */
    val PopupEnter: EnterTransition =
        fadeIn(tween(Fast, easing = Standard)) + scaleIn(tween(Medium, easing = Standard), initialScale = 0.92f)
    val PopupExit: ExitTransition =
        fadeOut(tween(Fast, easing = Accelerate)) + scaleOut(tween(Fast, easing = Accelerate), targetScale = 0.96f)

    /** A label that grows beside an icon (the selected bottom-bar tab). */
    val LabelEnter: EnterTransition =
        expandHorizontally(tween(Medium, easing = Standard)) + fadeIn(tween(Medium, delayMillis = 40, easing = Standard))
    val LabelExit: ExitTransition =
        shrinkHorizontally(tween(Exit, easing = Accelerate)) + fadeOut(tween(Fast))

    /** A skeleton giving way to content, or one empty/error/content state to another. */
    val SwapEnter: EnterTransition = fadeIn(tween(Medium, easing = Standard))
    val SwapExit: ExitTransition = fadeOut(tween(Fast))
}

// ---- Lists ----

/** Fade items in as they arrive and slide neighbours into the gap; a removed item fades out quickly. */
fun LazyItemScope.itemMotion(): Modifier = Modifier.animateItem(
    fadeInSpec = tween(PureTvMotion.Medium, easing = PureTvMotion.Standard),
    placementSpec = PureTvMotion.PlacementSpring,
    fadeOutSpec = tween(PureTvMotion.Fast),
)

fun LazyGridItemScope.itemMotion(): Modifier = Modifier.animateItem(
    fadeInSpec = tween(PureTvMotion.Medium, easing = PureTvMotion.Standard),
    placementSpec = PureTvMotion.PlacementSpring,
    fadeOutSpec = tween(PureTvMotion.Fast),
)

/** True when the viewer turned animations off. Provided once by [PureTvTheme]. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Reads the system animator duration scale (Developer options, or Accessibility
 * "Remove animations") and follows it live. One ContentObserver per app window,
 * registered only while the theme is composed.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember { mutableStateOf(readReducedMotion(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = readReducedMotion(context)
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

/** The system animator scale, 1 when unreadable. Shared with non-Compose callers such as the image loader. */
fun animatorDurationScale(context: Context): Float =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
        .getOrDefault(1f)

private fun readReducedMotion(context: Context): Boolean = MotionTokens.isReducedMotion(animatorDurationScale(context))
