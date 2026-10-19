package com.application.bibleapp.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

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
