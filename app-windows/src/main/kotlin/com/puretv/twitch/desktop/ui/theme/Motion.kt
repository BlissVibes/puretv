package com.puretv.twitch.desktop.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * The one motion vocabulary for the desktop app. Everything animated in `ui/` pulls
 * its duration, curve and spring from here, so a change in feel is a change in one file.
 *
 * Durations: [Fast] for things leaving, [Medium] for things entering (a panel, a bar,
 * a screen), [Slow] for anything bigger. Hover and press are springs, not durations.
 * Nothing is linear.
 *
 * Curves: [Standard] is the default for colour and layout (quick start, long settle).
 * [Decelerate] is for things ENTERING (they arrive fast and land softly), [Accelerate]
 * for things LEAVING (they pick up speed and are gone). Leaving is always shorter than
 * entering so the UI never feels like it is making you wait.
 *
 * Springs follow Material 3 Expressive's motion physics (the `MotionScheme.expressive()`
 * values from androidx material3's ExpressiveMotionTokens, copied below because this
 * material3 version has no MotionScheme). Spatial springs move things and may overshoot
 * a little; effects springs drive colour and opacity and never overshoot. Anything the
 * user can interrupt (hover, press, selection) runs on a spring, because a spring keeps
 * its velocity when the target flips mid-flight and a tween restarts from rest.
 * [MorphSpring], [PressSpring] and [HoverSpring] are all the fast spatial spring.
 *
 * Rules the call sites follow:
 *  - Move things in a `graphicsLayer { }` lambda (scale, alpha, translation) so a frame
 *    costs a layer update, not a recomposition or a relayout.
 *  - Never animate size, alpha or scale of anything that hosts or sits over the native
 *    video surface; the heavyweight AWT canvas ignores Compose transforms and flickers.
 *    Panels beside the video use [panelEnter] and [panelExit] (above or below it) or
 *    [sidePanelSize] and [sidePanelReveal] (beside it): they fade while their size
 *    changes once, so the surface resizes once instead of every frame.
 *  - Nothing that feeds layout (padding, width, spacing) rides a spring that can
 *    overshoot: on the way to 0 it would go negative, and layout throws.
 *  - No infinite animation on a screen that is playing video (see [LocalQuietMotion]).
 *  - Direction carries meaning: going deeper slides content in from the right, going
 *    back reverses it (see [RouteTransition]).
 *
 * [reduced] is the central off switch. It is set from the Windows "Animation effects"
 * preference (see `platform/SystemAnimations.kt`) and turns every move, scale and
 * spatial spring below into a snap. Opacity fades and effects springs stay: they carry
 * no motion, and the springs are over in under 170 ms.
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

    /** Travel of a side panel's content (chat) as it fades in beside the video. */
    val PanelTravel: Dp = 24.dp

    /** One half of the LIVE dot's breathing cycle. */
    const val LivePulseMs = 1400

    /** Scale settles to within 0.1%; the default 1% threshold ends a press with a visible jump. */
    const val ScaleThreshold = 0.001f

    /**
     * True when the user turned Windows animation effects off. Snapshot state: a spec reads it
     * when it is built, and anything that decorates continuously (shimmer, LIVE pulse) reads it
     * in composition, so flipping it takes effect on screen at once.
     */
    var reduced: Boolean by mutableStateOf(false)

    // ── Curves ─────────────────────────────────────────────────────────────────

    /** Colour, opacity and layout changes that stay on screen. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Entering: fast start, soft landing. */
    val Decelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Leaving: slow start, quick exit. */
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    // ── Springs ────────────────────────────────────────────────────────────────

    // M3 Expressive spring tokens (ExpressiveMotionTokens in androidx.compose.material3).
    // Fast is for small things (buttons, chips, switches, corners), default for larger
    // ones (list items moving). Effects springs are critically damped by definition.

    /** Fast spatial: settles in about 270 ms with a small (~10%) overshoot. */
    const val SpatialFastDamping = 0.6f
    const val SpatialFastStiffness = 800f

    /** Default spatial: settles in about 300 ms with a barely visible overshoot. */
    const val SpatialDefaultDamping = 0.8f
    const val SpatialDefaultStiffness = 380f

    /** Fast effects (hover colour, focus ring): about 110 ms, no overshoot. */
    const val EffectsFastStiffness = 3800f

    /** Default effects (a state's colour or opacity): about 170 ms, no overshoot. */
    const val EffectsDefaultStiffness = 1600f

    /**
     * The morph curve: the fast spatial spring. Overshoots its target and settles back,
     * which is what makes a corner change read as elastic rather than as a plain tween.
     * Do NOT use it for anything that feeds layout (padding, width, spacing): an
     * overshoot there goes negative.
     */
    val MorphSpring: SpringSpec<Dp> =
        spring(SpatialFastDamping, SpatialFastStiffness, visibilityThreshold = Dp.VisibilityThreshold)

    /** Same feel, for float-valued properties (scale, translation). */
    val MorphSpringFloat: SpringSpec<Float> =
        spring(SpatialFastDamping, SpatialFastStiffness, visibilityThreshold = ScaleThreshold)

    /** Press scale. The overshoot on release is a fraction of a percent of the press depth. */
    val PressSpring: SpringSpec<Float> = MorphSpringFloat

    /** Hover lift (Dp). Hover scale uses [PressSpring]. */
    val HoverSpring: SpringSpec<Dp> = MorphSpring

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

    /**
     * Fast effects spring for hover colour and focus. Interruptible, so sweeping the
     * pointer across a grid never leaves a trail of cards finishing a fixed-length tween.
     * Not snapped when [reduced]: like [fade], it carries no motion.
     */
    fun <T> effectsFast(): FiniteAnimationSpec<T> = spring(stiffness = EffectsFastStiffness)

    /** Default effects spring, for a colour that marks a state (selected, checked). */
    fun <T> effects(): FiniteAnimationSpec<T> = spring(stiffness = EffectsDefaultStiffness)

    // ── Lazy list items ────────────────────────────────────────────────────────

    /** Fade for an item that appears in a lazy list after it first laid out. */
    fun itemFadeIn(): FiniteAnimationSpec<Float> = effects()

    /** Fade for an item that leaves a lazy list: quick, so the gap closes promptly. */
    fun itemFadeOut(): FiniteAnimationSpec<Float> = effectsFast()

    /**
     * Movement of lazy list items when the list reorders (a refresh re-ranking streams).
     * Null, so items jump to their new place, when [reduced].
     */
    fun itemPlacement(): FiniteAnimationSpec<IntOffset>? =
        if (reduced) null else spring(SpatialDefaultDamping, SpatialDefaultStiffness, IntOffset.VisibilityThreshold)

    // ── Side panels beside the video ───────────────────────────────────────────

    /**
     * Width (or gap) of a panel that sits BESIDE the native video surface, such as chat.
     * It takes its width in one step when opening and gives it back in one step once
     * [sidePanelReveal] has faded the content out, so the video resizes once per
     * toggle instead of on every frame. [instant] when the window itself is changing
     * shape (mini player).
     */
    fun <T> sidePanelSize(open: Boolean, instant: Boolean = false): FiniteAnimationSpec<T> =
        snap(delayMillis = if (open || instant) 0 else Fast)

    /** Progress (0 hidden, 1 shown) of the content inside a [sidePanelSize] panel. Use with [Modifier.sidePanelReveal]. */
    fun sidePanelReveal(open: Boolean, instant: Boolean = false): FiniteAnimationSpec<Float> = when {
        instant -> snap()
        open -> fade(Medium, Decelerate)
        else -> fade(Fast, Accelerate)
    }

    // ── Stagger ────────────────────────────────────────────────────────────────

    /** Items past this index appear at once: a late, far-down item must never wait on a delay. */
    const val StaggerMaxItems = 8
    const val StaggerStepMs = 20

    /**
     * Delay before the item at [index] starts appearing: 20 ms per item, capped at
     * [StaggerMaxItems] items so the last one starts by 140 ms and the group is in
     * within ~360 ms. Negative indexes clamp to zero. Pure, so it can be unit tested.
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

    /**
     * The navigation rail returning when the player leaves an immersive mode. Its slot
     * comes back in one step, together with the shell gutter, and the rail slides into
     * it. It has no exit animation on purpose: going immersive switches the whole shell
     * in a single frame, so the video resizes once instead of once per frame of a slide.
     */
    fun railEnter(): EnterTransition =
        if (reduced) {
            fadeIn(tween(Fast))
        } else {
            slideInHorizontally(tween(Medium, easing = Decelerate)) { -it } + fadeIn(tween(Fast, easing = Decelerate))
        }
}

/**
 * Fades the content of a [PureTvMotion.sidePanelSize] panel in while it slides in from
 * the right, the side such panels sit on. Runs in the layer, so a frame costs a layer
 * update. Callers clip the panel to its bounds. [progress] is
 * read in the layer block, so pass a lambda over animated state.
 */
fun Modifier.sidePanelReveal(progress: () -> Float): Modifier = graphicsLayer {
    val p = progress()
    alpha = p
    if (!PureTvMotion.reduced) translationX = (1f - p) * PureTvMotion.PanelTravel.toPx()
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
    // Keyed, so a new screen gets a fresh progress at 0 in the very composition that shows it;
    // resetting from an effect would draw one frame at full opacity first.
    val progress = remember(key) { Animatable(0f) }
    val enter = remember(key) { RouteEnterState(entering = true) }
    LaunchedEffect(key) {
        progress.animateTo(1f, PureTvMotion.move(PureTvMotion.Medium, PureTvMotion.Decelerate))
        enter.entering = false
    }
    CompositionLocalProvider(LocalRouteEnter provides enter) {
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
}

/** True while the enclosing [RouteTransition] is still fading in; children skip their own entrance then. */
class RouteEnterState(@Volatile var entering: Boolean)

val LocalRouteEnter = compositionLocalOf { RouteEnterState(entering = false) }

/**
 * Remembers which items of a screen have already played [staggerIn], by key, so an item
 * that scrolls out of a lazy grid and back does not animate again. Keep one per screen.
 */
class StaggerLedger {
    private val played = HashSet<Any>()

    /** True the first time [key] is seen. */
    fun firstTime(key: Any): Boolean = played.add(key)
}

/**
 * One-shot fade and rise for the item at [index] of a freshly loaded group (a shelf of
 * cards replacing its skeletons). It plays once per [key] ([ledger]), only for the first
 * [PureTvMotion.StaggerMaxItems] items, and not at all while the screen itself is still
 * fading in ([RouteTransition]), which would stack two fades. Runs in `graphicsLayer`.
 */
fun Modifier.staggerIn(index: Int, key: Any, ledger: StaggerLedger): Modifier = composed(
    factory = {
        val routeEnter = LocalRouteEnter.current
        val animate = remember {
            val first = ledger.firstTime(key)
            first && index < PureTvMotion.StaggerMaxItems && !routeEnter.entering
        }
        if (!animate) {
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
