package com.puretv.twitch.desktop.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one motion vocabulary for the desktop app. Everything animated in `ui/` pulls
 * its duration, curve and spring from here, so a change in feel is a change in one file.
 *
 * Durations: [Fast] for hover and press feedback, [Medium] for a state change or a
 * panel, [Slow] for a structural change (the window shell). Nothing is linear.
 *
 * Curves: [Standard] is the default for colour and layout (quick start, long settle).
 * [Decelerate] is for things ENTERING (they arrive fast and land softly), [Accelerate]
 * for things LEAVING (they pick up speed and are gone). Leaving is always shorter than
 * entering so the UI never feels like it is making you wait.
 *
 * Springs: [MorphSpring] is the one deliberately bouncy spring, reserved for corner
 * radius morphs and switch thumbs. [PressSpring] and [HoverSpring] are low bounce and
 * stiff, for scale and lift, so a press settles in about 150 ms instead of wobbling.
 *
 * Rules the call sites follow:
 *  - Move things in a `graphicsLayer { }` lambda (scale, alpha, translation) so a frame
 *    costs a layer update, not a recomposition or a relayout.
 *  - Never animate size, alpha or scale of anything that hosts or sits over the native
 *    video surface; the heavyweight AWT canvas ignores Compose transforms and flickers.
 *    Panels beside the video use [panelEnter] and [panelExit]: they fade while their
 *    size changes once, so the surface resizes once instead of every frame.
 *  - No infinite animation on a screen that is playing video (see [LocalQuietMotion]).
 *  - Direction carries meaning: going deeper slides content in from the right, going
 *    back reverses it (see [RouteTransition]).
 *
 * [reduced] is the central off switch. It is set from the Windows "Animation effects"
 * preference (see `platform/SystemAnimations.kt`) and turns every move, scale and
 * spring below into a snap. Opacity fades stay: they carry no motion.
 */
object PureTvMotion {
    const val Fast = 120
    const val Medium = 220
    const val Slow = 350
    const val ControlsAutoHideMs = 2800L

    /** Press-in scale for buttons, chips, rows. */
    const val PressScale = 0.97f

    /** Press-in scale for large surfaces (cards, tiles), where 3% would be a visible lurch. */
    const val CardPressScale = 0.985f

    /** Pixels of travel for a route change. Small on purpose: it hints direction, it is not a swipe. */
    val RouteTravel: Dp = 28.dp

    /** True when the user turned Windows animation effects off. Read at the moment a spec is built. */
    @Volatile
    var reduced: Boolean = false

    // ── Curves ─────────────────────────────────────────────────────────────────

    /** Colour, opacity and layout changes that stay on screen. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Entering: fast start, soft landing. */
    val Decelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Leaving: slow start, quick exit. */
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    // ── Springs ────────────────────────────────────────────────────────────────

    /**
     * The morph curve. Overshoots its target and settles back, which is what makes
     * a corner change read as elastic rather than as a plain tween. The CSS
     * mockup expresses the same thing as `cubic-bezier(.2, 1.4, .3, 1)`. Note the
     * 1.4 control point above 1. Do NOT use it for anything that feeds layout
     * (padding, width, spacing): an overshoot there goes negative.
     */
    val MorphSpring: SpringSpec<Dp> = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)

    /** Same feel, for float-valued properties (scale, translation, alpha ramps). */
    val MorphSpringFloat: SpringSpec<Float> = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)

    /** Press scale: stiff and almost critically damped, one barely visible settle. */
    val PressSpring: SpringSpec<Float> = spring(dampingRatio = 0.75f, stiffness = 900f)

    /** Hover lift (Dp) and hover scale (Float): calmer than a press. */
    val HoverSpring: SpringSpec<Dp> = spring(dampingRatio = 0.85f, stiffness = 600f)

    // ── Reduced-motion aware specs ─────────────────────────────────────────────

    /** A tween for movement (translation, size, scale). Snaps when [reduced]. */
    fun <T> move(durationMs: Int = Medium, easing: Easing = Standard, delayMs: Int = 0): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(durationMs, delayMs, easing)

    /** A tween for opacity and colour. Never snapped: a fade is not motion. */
    fun <T> fade(durationMs: Int = Medium, easing: Easing = Standard, delayMs: Int = 0): FiniteAnimationSpec<T> =
        tween(durationMs, delayMs, easing)

    /** [PressSpring] as a spec that respects [reduced]. */
    fun press(): FiniteAnimationSpec<Float> = if (reduced) snap() else PressSpring

    /** [HoverSpring] as a spec that respects [reduced]. */
    fun hover(): FiniteAnimationSpec<Dp> = if (reduced) snap() else HoverSpring

    /** [MorphSpring] as a spec that respects [reduced]. */
    fun morph(): FiniteAnimationSpec<Dp> = if (reduced) snap() else MorphSpring

    /** [MorphSpringFloat] as a spec that respects [reduced]. */
    fun morphFloat(): FiniteAnimationSpec<Float> = if (reduced) snap() else MorphSpringFloat

    // ── Stagger ────────────────────────────────────────────────────────────────

    /** Items past this index appear at once: a late, far-down item must never wait on a delay. */
    const val StaggerMaxItems = 10
    const val StaggerStepMs = 28

    /**
     * Delay before the item at [index] starts appearing: 28 ms per item, capped at
     * [StaggerMaxItems] items so the whole group is in within ~280 ms. Negative
     * indexes clamp to zero. Pure, so it can be unit tested.
     */
    fun staggerDelayMs(index: Int, stepMs: Int = StaggerStepMs, maxItems: Int = StaggerMaxItems): Int =
        index.coerceIn(0, maxItems) * stepMs

    // ── Enter and exit transitions ─────────────────────────────────────────────

    /**
     * A bar docked to the top ([fromTop]) or bottom of the player column: slides in
     * along its own edge while fading. Layout size is untouched by a slide, so the
     * video resizes once, when the bar is added or removed.
     */
    fun barEnter(fromTop: Boolean): EnterTransition =
        if (reduced) {
            fadeIn(tween(Fast))
        } else {
            slideInVertically(tween(Medium, easing = Decelerate)) { if (fromTop) -it else it } +
                fadeIn(tween(Medium, easing = Decelerate))
        }

    /** Leaving is quicker than entering. Pass [instant] when the window itself is changing shape (mini player). */
    fun barExit(fromTop: Boolean, instant: Boolean = false): ExitTransition =
        when {
            instant -> ExitTransition.None
            reduced -> fadeOut(tween(Fast))
            else -> slideOutVertically(tween(Fast + 30, easing = Accelerate)) { if (fromTop) -it else it } +
                fadeOut(tween(Fast, easing = Accelerate))
        }

    /**
     * A panel that sits between the video and the controls (playback menu, notices,
     * shortcut sheet, banners). The content fades in while the panel takes its full
     * height at once, so the native video surface resizes a single time.
     */
    fun panelEnter(): EnterTransition =
        expandVertically(snap()) + fadeIn(tween(Medium, delayMillis = 30, easing = Decelerate))

    /** Mirror of [panelEnter]: fade first, then give the height back in one step. */
    fun panelExit(): ExitTransition =
        fadeOut(tween(Fast, easing = Accelerate)) + shrinkVertically(snap(delayMillis = Fast))
}

/**
 * Set on screens that are playing video. Continuous decoration (the LIVE dot pulse)
 * stops there: a surface that is already decoding and drawing 60 fps of video must not
 * also keep a Compose animation clock running for ornament.
 */
val LocalQuietMotion = staticCompositionLocalOf { false }

/**
 * Depth of a route in the navigation stack, for choosing a transition direction.
 * Top-level screens are 0, a channel or category page 1, a player 2.
 */
enum class RouteDepth { Top, Detail, Player }

/** Sign of the travel between two routes: +1 going deeper, -1 going back, 0 sideways. */
fun routeDirection(from: RouteDepth, to: RouteDepth): Int = to.ordinal.compareTo(from.ordinal).coerceIn(-1, 1)

/**
 * Enter-only transition for a screen. When [key] changes (a new route, or a new
 * top-level destination) the content fades in while travelling [PureTvMotion.RouteTravel]
 * along [direction] (+1 from the right, -1 from the left, 0 rises a few dp).
 *
 * Why enter-only and not AnimatedContent: AnimatedContent keeps the OUTGOING screen
 * composed for the length of the transition. For a channel page that is harmless, but
 * the player would keep playing and chatting while it faded, and two players could
 * briefly exist. Here the old screen is gone at once, the new one eases in, and input
 * is never blocked (alpha and translation do not affect hit testing).
 *
 * Do NOT wrap screens that host the native video surface; they swap instantly. Alpha
 * and translation on a parent of the heavyweight canvas make it flicker.
 */
@Composable
fun RouteTransition(key: Any, direction: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(key) {
        progress.snapTo(0f)
        progress.animateTo(1f, PureTvMotion.move(PureTvMotion.Medium, PureTvMotion.Decelerate))
    }
    Box(
        modifier.graphicsLayer {
            val p = progress.value
            alpha = p
            val travel = PureTvMotion.RouteTravel.toPx()
            val offset = (1f - p) * travel
            if (direction == 0) translationY = offset * 0.35f else translationX = offset * direction
            // The layer is only translucent for ~220 ms; modulating alpha per draw call is
            // cheaper than an offscreen buffer the size of the whole content pane.
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
    ) { content() }
}

/**
 * One-shot fade and rise for the item at [index] of a freshly loaded group (a shelf of
 * cards replacing its skeletons). Items past [PureTvMotion.StaggerMaxItems] get no
 * animation at all, so scrolling a long grid never plays it. Runs in `graphicsLayer`.
 */
fun Modifier.staggerIn(index: Int): Modifier = composed(
    factory = {
        if (index >= PureTvMotion.StaggerMaxItems) {
            Modifier
        } else {
            val progress = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                progress.animateTo(
                    1f,
                    PureTvMotion.fade<Float>(PureTvMotion.Medium, PureTvMotion.Decelerate, PureTvMotion.staggerDelayMs(index)),
                )
            }
            Modifier.graphicsLayer {
                val p = progress.value
                alpha = p
                if (!PureTvMotion.reduced) translationY = (1f - p) * 12.dp.toPx()
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
        }
    },
)
