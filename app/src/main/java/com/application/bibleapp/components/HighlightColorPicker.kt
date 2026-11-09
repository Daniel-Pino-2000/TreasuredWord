package com.application.bibleapp.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.application.bibleapp.ui.theme.HighlightColors
import com.application.bibleapp.ui.theme.toHighlightArgb
import com.application.bibleapp.R
import androidx.compose.ui.res.stringResource

/** Display name per [HighlightColors.palette] entry, in the same order — screen-reader label only. */
internal val SWATCH_NAMES = listOf(
    R.string.highlight_color_yellow,
    R.string.highlight_color_green,
    R.string.highlight_color_blue,
    R.string.highlight_color_pink,
    R.string.highlight_color_purple
)

/**
 * A row of solid swatches from [HighlightColors.palette] — shared between [VerseSelectionToolbar]
 * (new highlight, no current color to ring) and [HighlightActionSheet] (recoloring — pass the
 * highlight's current [selectedColorArgb] to ring it), so the two pickers can't visually drift
 * apart. [onColorSelected] receives the swatch's stored ARGB (translucent — see [toHighlightArgb]),
 * ready to pass straight to BibleViewModel.
 */
@Composable
fun HighlightColorSwatchRow(
    onColorSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selectedColorArgb: Int? = null,
    swatchSize: Dp = 40.dp
) {
    // Each swatch gets an equal share of the row as its touch area, at least 48dp tall, with the
    // circle centered in it (#248): a plain selectable Box adds no minimum touch size the way
    // Material buttons do, so only the circle itself used to be tappable.
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HighlightColors.palette.forEachIndexed { index, swatch ->
            val swatchArgb = swatch.toHighlightArgb()
            val isSelected = selectedColorArgb != null && selectedColorArgb == swatchArgb
            val name = stringResource(SWATCH_NAMES.getOrElse(index) { R.string.highlight_color_generic })
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = SWATCH_TOUCH_SIZE)
                    .selectable(
                        selected = isSelected,
                        interactionSource = null,
                        indication = ripple(bounded = false, radius = SWATCH_TOUCH_SIZE / 2),
                        onClick = { onColorSelected(swatchArgb) }
                    )
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center
            ) {
                HighlightSwatchCircle(swatch, isSelected, swatchSize, ringColor = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** The smallest touch area a swatch gets, Android's accessibility minimum (#248). */
internal val SWATCH_TOUCH_SIZE = 48.dp

/** One solid swatch circle, ringed and checked when [isSelected]. Purely visual: the caller's
 *  touch area around it handles taps and semantics. */
@Composable
internal fun HighlightSwatchCircle(swatch: Color, isSelected: Boolean, size: Dp, ringColor: Color) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(swatch)
            .then(if (isSelected) Modifier.border(2.5.dp, ringColor, CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = HighlightColors.CheckMark,
                modifier = Modifier.size(size * 0.45f)
            )
        }
    }
}
