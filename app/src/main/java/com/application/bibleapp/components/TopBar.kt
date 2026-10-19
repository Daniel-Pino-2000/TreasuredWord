package com.application.bibleapp.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.application.bibleapp.ui.theme.isCompactHeight

private val COMPACT_TOP_BAR_HEIGHT = 56.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    title: @Composable () -> Unit = { Text("BibleApp") },
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

    TopAppBar(
        title = title,
        navigationIcon = navigationIcon ?: {},
        actions = actions,
        // A phone in landscape has ~360dp of height in total; the slimmer bar hands 8dp back
        // to the content on every screen.
        expandedHeight = if (isCompactHeight()) COMPACT_TOP_BAR_HEIGHT else TopAppBarDefaults.TopAppBarExpandedHeight,
        scrollBehavior = scrollBehavior,
        modifier = modifier.background(backgroundColor),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        )
    )
}