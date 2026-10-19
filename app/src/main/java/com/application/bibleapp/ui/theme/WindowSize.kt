package com.application.bibleapp.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Material's `WindowHeightSizeClass.Compact` breakpoint — a phone in landscape sits well below it. */
private const val COMPACT_HEIGHT_BREAKPOINT_DP = 480

/**
 * True when the window is too short for the portrait layout (a phone in landscape): the app then
 * swaps its bottom bar for a side rail, slims the reader chrome, and packs sheets/toolbars tighter.
 * Read straight from the configuration rather than pulling in material3-adaptive for one comparison.
 */
@Composable
@ReadOnlyComposable
fun isCompactHeight(): Boolean = LocalConfiguration.current.screenHeightDp < COMPACT_HEIGHT_BREAKPOINT_DP

/** Widest a screen's content column gets — the same ~65-70 characters-per-line cap the reader
 *  uses (BibleText's MAX_READING_WIDTH), so cards and lists don't stretch edge to edge on a
 *  landscape phone or tablet. Phones in portrait never reach it. */
val MaxContentWidth: Dp = 600.dp

/** Fills the available space but lays the content out in a centered column at most [maxWidth]
 *  wide. Applied per destination in Navigation, ahead of each screen's own modifiers. */
fun Modifier.centeredContentWidth(maxWidth: Dp = MaxContentWidth): Modifier =
    this
        .fillMaxSize()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = maxWidth)
