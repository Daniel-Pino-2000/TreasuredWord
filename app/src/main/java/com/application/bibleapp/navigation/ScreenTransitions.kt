package com.application.bibleapp.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.dp

/**
 * How one screen gives way to the next (#241), following Material's motion patterns. Navigation's
 * default was a 700 ms crossfade that showed both pages at once, half see-through, while the top
 * bar and the tab bar or rail had already switched.
 *
 * - Between two tabs, a fade through: the old page fades out quickly, then the new one fades in,
 *   so two pages never show at once.
 * - Into a screen opened from somewhere (Sign in, Settings, Library, the pickers) and back out, a
 *   shared axis X: the same fades, plus a short slide forward or back.
 *
 * The top bar's contents and the tab bar or rail use the same timings (MainActivity), so the
 * screen changes as one piece.
 */
object ScreenTransitions {
    /** The old page is gone this quickly... */
    const val FADE_OUT_MILLIS = 90

    /** ...and the new one fades in over this long, right after. */
    const val FADE_IN_MILLIS = 210

    /** The whole change; also how long the tab bar and rail take to slide in or out. */
    const val DURATION_MILLIS = FADE_OUT_MILLIS + FADE_IN_MILLIS

    /** Material's shared-axis slide distance. */
    val SlideDistance = 30.dp

    private val tabRoutes: Set<String> by lazy { bottomNavigationItems.map { it.route }.toSet() }

    private fun betweenTabs(from: String?, to: String?): Boolean = from in tabRoutes && to in tabRoutes

    fun fadeInAfterOut(): EnterTransition =
        fadeIn(tween(FADE_IN_MILLIS, delayMillis = FADE_OUT_MILLIS, easing = LinearOutSlowInEasing))

    fun fadeOutFirst(): ExitTransition = fadeOut(tween(FADE_OUT_MILLIS, easing = FastOutLinearInEasing))

    /**
     * The page coming in. [forward] is false when going back; [slidePx] is [SlideDistance] in
     * pixels (the transition scopes have no density of their own).
     */
    fun enter(from: String?, to: String?, forward: Boolean, slidePx: Int): EnterTransition =
        if (betweenTabs(from, to)) {
            fadeInAfterOut()
        } else {
            fadeInAfterOut() + slideInHorizontally(tween(DURATION_MILLIS, easing = FastOutSlowInEasing)) {
                if (forward) slidePx else -slidePx
            }
        }

    /** The page going out; see [enter]. */
    fun exit(from: String?, to: String?, forward: Boolean, slidePx: Int): ExitTransition =
        if (betweenTabs(from, to)) {
            fadeOutFirst()
        } else {
            fadeOutFirst() + slideOutHorizontally(tween(DURATION_MILLIS, easing = FastOutSlowInEasing)) {
                if (forward) -slidePx else slidePx
            }
        }
}
