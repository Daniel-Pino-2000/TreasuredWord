package com.application.bibleapp.components

import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.application.bibleapp.ui.theme.BibleAppTheme
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.ThemePalette
import com.application.bibleapp.ui.theme.ThemePalettePreviewProvider

/**
 * False when the phone's "Remove animations" (animator duration scale 0) is on, so the Bible
 * Helper's writing state stays still instead of pulsing (#283).
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}

/** Three dots that pulse one after another while the Bible Helper writes (#283). Decorative. */
@Composable
fun AiTypingDots(modifier: Modifier = Modifier) {
    val animate = rememberAnimationsEnabled()
    val transition = rememberInfiniteTransition(label = "typing dots")
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(DOT_GAP), verticalAlignment = Alignment.CenterVertically) {
        repeat(DOT_COUNT) { index ->
            val alpha by transition.animateFloat(
                initialValue = DIM_ALPHA,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(DOT_PULSE_MILLIS, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * DOT_PULSE_MILLIS / DOT_COUNT)
                ),
                label = "dot $index"
            )
            Box(
                modifier = Modifier
                    .size(DOT_SIZE)
                    .alpha(if (animate) alpha else 1f)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}

/**
 * Faint lines where the answer will appear, softly pulsing (#283). Decorative: the step text next
 * to the dots says what's happening.
 */
@Composable
fun AiAnswerPlaceholder(modifier: Modifier = Modifier) {
    val animate = rememberAnimationsEnabled()
    val transition = rememberInfiniteTransition(label = "answer placeholder")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = DIM_ALPHA,
        animationSpec = infiniteRepeatable(tween(LINES_PULSE_MILLIS), RepeatMode.Reverse),
        label = "lines"
    )
    Column(
        modifier = modifier.fillMaxWidth().alpha(if (animate) alpha else 1f),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        LINE_WIDTHS.forEach { width ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(width)
                    .height(LINE_HEIGHT)
                    .background(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)
            )
        }
    }
}

private const val DOT_COUNT = 3
private val DOT_SIZE = 8.dp
private val DOT_GAP = 6.dp
private const val DOT_PULSE_MILLIS = 600
private const val LINES_PULSE_MILLIS = 900
private const val DIM_ALPHA = 0.3f
private val LINE_HEIGHT = 14.dp
private val LINE_WIDTHS = listOf(1f, 0.92f, 0.6f)

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AiWritingIndicatorPreview(@PreviewParameter(ThemePalettePreviewProvider::class) palette: ThemePalette) {
    BibleAppTheme(palette = palette) {
        Surface {
            Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                AiTypingDots()
                AiAnswerPlaceholder()
            }
        }
    }
}
