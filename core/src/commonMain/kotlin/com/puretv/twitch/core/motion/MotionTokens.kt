package com.puretv.twitch.core.motion

/**
 * The numbers behind phone and TV motion, kept free of Compose so both apps read
 * the same values and the pure rules can be unit tested.
 */
object MotionTokens {
    /** Press and focus feedback, nav fades. Short enough to read as instant. */
    const val FastMs = 120

    /** Most state swaps: colour, banners, control fades. */
    const val MediumMs = 220

    /** Entrances that carry a lot of travel, such as a hero or a sheet. */
    const val SlowMs = 350

    /** Exits run faster than entrances, so leaving never feels like waiting. */
    const val ExitMs = 160

    /** Phone press shrink. */
    const val PressScale = 0.97f

    /** TV focus growth. Small enough not to overlap neighbouring cards. */
    const val TvFocusScale = 1.06f

    /** Gap between successive list items entering, and the number that stagger. */
    const val StaggerStepMs = 30
    const val StaggerMaxSteps = 8

    /**
     * Delay for the [index]th item of a list that is entering together. Capped, so a
     * long list never makes its tail wait, and zero when motion is reduced.
     */
    fun staggerDelayMs(index: Int, reduced: Boolean = false): Int =
        if (reduced || index <= 0) 0 else minOf(index, StaggerMaxSteps) * StaggerStepMs

    /**
     * Whether the viewer asked for no motion. The system "remove animations"
     * setting writes an animator duration scale of 0; a missing or negative value
     * is treated as the default 1x.
     */
    fun isReducedMotion(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

    /** Scale a duration by the system animator scale: 0 stays 0, anything else is at least 1 ms. */
    fun scaledDurationMs(baseMs: Int, animatorDurationScale: Float): Int = when {
        animatorDurationScale == 0f -> 0
        animatorDurationScale < 0f || animatorDurationScale.isNaN() -> baseMs
        else -> (baseMs * animatorDurationScale).toInt().coerceAtLeast(1)
    }

    /**
     * Scroll distance that puts a child's centre on the container's centre, or 0 when
     * it is already there. A child at least as large as the container aligns its
     * leading edge instead, so its start is never scrolled out of view. [offset] is
     * the child's leading edge relative to the container's.
     */
    fun centredScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (size >= containerSize) return offset
        return offset - (containerSize - size) / 2f
    }

    /**
     * Like [centredScrollDistance] but only once the child leaves the central band
     * that keeps [bandFraction] of the container free at each end, so a page holds
     * still while focus moves around comfortably inside it.
     */
    fun bandedScrollDistance(offset: Float, size: Float, containerSize: Float, bandFraction: Float): Float {
        // Like Compose's default, leave a child that fills the viewport where it is.
        if (size >= containerSize) return 0f
        val margin = containerSize * bandFraction
        val inside = offset >= margin && offset + size <= containerSize - margin
        return if (inside) 0f else centredScrollDistance(offset, size, containerSize)
    }
}
