package com.application.bibleapp.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.application.bibleapp.ui.theme.HighlightColors
import com.application.bibleapp.ui.theme.toHighlightArgb

/** Display name per [HighlightColors.palette] entry, in the same order — screen-reader label only. */
private val SWATCH_NAMES = listOf("Yellow", "Green", "Blue", "Pink", "Purple")

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
    selectedColorArgb: Int? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        HighlightColors.palette.forEachIndexed { index, swatch ->
            val swatchArgb = swatch.toHighlightArgb()
            val isSelected = selectedColorArgb != null && selectedColorArgb == swatchArgb
            val name = SWATCH_NAMES.getOrElse(index) { "Highlight color" }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .then(
                        if (isSelected) {
                            Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        } else {
                            Modifier
                        }
                    )
                    .selectable(selected = isSelected, onClick = { onColorSelected(swatchArgb) })
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
