package com.application.bibleapp.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.application.bibleapp.ui.theme.isCompactHeight
import com.application.bibleapp.ui.theme.rememberKeyboardOpenFraction
import com.application.bibleapp.R
import androidx.compose.ui.res.stringResource

private val COMPACT_TOP_BAR_HEIGHT = 56.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    title: @Composable () -> Unit = { Text(stringResource(R.string.app_name)) },
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    modifier: Modifier = Modifier
) {
    // M3's TopAppBar internally runs its own containerColor through
    // animateColorAsState(spring(...)) to smooth the tonal shift on scroll. That
    // also catches a theme toggle, so the bar's background visibly lags a beat
    // behind every other composable here (which reads colorScheme directly and
    // snaps instantly). We paint the real background ourselves — un-animated, so
    // it updates in the same frame as the rest of the theme — and hand the
    // TopAppBar fully transparent colors so its internal spring has nothing
    // visible left to lag on. lerp-ing by the same scroll fraction M3 uses keeps
    // the original "faint tonal shift on scroll" feedback.
    val scrollFraction = scrollBehavior?.state?.overlappedFraction?.coerceIn(0f, 1f) ?: 0f
    val backgroundColor = lerp(
        MaterialTheme.colorScheme.background,
        MaterialTheme.colorScheme.surface,
        scrollFraction
    )
    val compactHeight = isCompactHeight()
    // A phone in landscape has ~360dp of height in total; the slimmer bar hands 8dp back
    // to the content on every screen.
    val expandedHeight = if (compactHeight) COMPACT_TOP_BAR_HEIGHT else TopAppBarDefaults.TopAppBarExpandedHeight

    TopAppBar(
        title = title,
        navigationIcon = navigationIcon ?: {},
        actions = actions,
        expandedHeight = expandedHeight,
        // In landscape the keyboard leaves ~100dp above it, less than this bar plus a text field,
        // so the bar steps aside while typing (#215). It collapses with the keyboard, the way the
        // reader's bar collapses on scroll, instead of popping out and back in (#239). The
        // reader brings its own scroll behavior and has no text field.
        scrollBehavior = scrollBehavior ?: if (compactHeight) rememberKeyboardCollapseBehavior(expandedHeight) else null,
        modifier = modifier.background(backgroundColor),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        )
    )
}

/**
 * Collapses the bar by how far the keyboard is open, using Material's own collapse (the height
 * offset the reader's scroll behavior drives): the title slides up and is clipped below the
 * status bar, whose strip keeps the bar's color. Pinned, so dragging the bar does nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberKeyboardCollapseBehavior(expandedHeight: Dp): TopAppBarScrollBehavior {
    val keyboardOpen = rememberKeyboardOpenFraction()
    val expandedHeightPx = with(LocalDensity.current) { expandedHeight.toPx() }
    // Starts at the keyboard's current position, so a bar shown while typing (another screen
    // opened, the phone turned) doesn't draw one frame at full height first.
    val behavior = remember {
        KeyboardCollapseBehavior(
            TopAppBarState(
                initialHeightOffsetLimit = -expandedHeightPx,
                initialHeightOffset = -expandedHeightPx * keyboardOpen.value,
                initialContentOffset = 0f
            )
        )
    }
    LaunchedEffect(behavior, expandedHeightPx) {
        snapshotFlow { keyboardOpen.value }.collect { behavior.state.heightOffset = -expandedHeightPx * it }
    }
    return behavior
}

@OptIn(ExperimentalMaterial3Api::class)
private class KeyboardCollapseBehavior(override val state: TopAppBarState) : TopAppBarScrollBehavior {
    override val isPinned: Boolean = true
    override val snapAnimationSpec: AnimationSpec<Float>? = null
    override val flingAnimationSpec: DecayAnimationSpec<Float>? = null
    override val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {}
}
