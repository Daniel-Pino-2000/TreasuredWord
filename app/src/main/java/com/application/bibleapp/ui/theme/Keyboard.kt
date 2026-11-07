package com.application.bibleapp.ui.theme

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt

/**
 * How far the on-screen keyboard is open: 0f closed, 1f fully open, following the keyboard's own
 * open and close animation frame by frame. What steps aside for the keyboard in landscape (the
 * top bar, Library's tabs) collapses by this, so it moves with the keyboard instead of popping
 * in or out once the keyboard has stopped (#239). Read it in layout, through a lambda, so only
 * layout reruns on each frame.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberKeyboardOpenFraction(): State<Float> {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val source = WindowInsets.imeAnimationSource
    val target = WindowInsets.imeAnimationTarget
    return remember(density, ime, source, target) {
        derivedStateOf {
            val from = source.getBottom(density)
            val to = target.getBottom(density)
            when {
                // Open before and after: the keyboard is only changing height (its suggestion
                // strip, another layout). Stay fully open rather than wobble.
                from > 0 && to > 0 -> 1f
                // Opening or closing (or still): measured against its full height, which is
                // where it's heading while it opens and where it started while it closes.
                else -> {
                    val full = maxOf(from, to)
                    if (full <= 0) 0f else (ime.getBottom(density).toFloat() / full).coerceIn(0f, 1f)
                }
            }
        }
    }
}

/**
 * Whether the keyboard is open or on its way: true as soon as it starts to open, false as soon as
 * it starts to close. `WindowInsets.isImeVisible` only turns false once the keyboard is fully
 * gone, so a layout switched by it changed after the keyboard had finished moving, which read
 * as a jump (#239).
 *
 * Call it inside the window being typed in: in a bottom sheet, inside the sheet's content. The
 * keyboard's animation only reaches the window it types into; the screen behind a sheet just
 * gets the end result, late.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun isKeyboardOpenOrOpening(): Boolean =
    WindowInsets.imeAnimationTarget.getBottom(LocalDensity.current) > 0

/**
 * Collapses this upward by [fraction] (0f fully shown, 1f gone): the reported height shrinks, so
 * what's below moves up into the room, and the content slides up out of view, clipped, the way
 * a top bar scrolls away.
 */
fun Modifier.collapseUpward(fraction: () -> Float): Modifier =
    this
        .clipToBounds()
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val height = (placeable.height * (1f - fraction().coerceIn(0f, 1f))).roundToInt()
            layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
        }
