package com.application.bibleapp.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.application.bibleapp.ui.theme.HighlightColors
import com.application.bibleapp.ui.theme.toHighlightArgb

/** A row of solid swatches from [HighlightColors.palette] — shared between [VerseSelectionToolbar]
 *  (new highlight) and [HighlightActionSheet] (recoloring an existing one), so the two pickers
 *  can't visually drift apart. [onColorSelected] receives the swatch's stored ARGB (translucent —
 *  see [toHighlightArgb]), ready to pass straight to BibleViewModel. */
@Composable
fun HighlightColorSwatchRow(onColorSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        HighlightColors.palette.forEach { swatch ->
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .clickable { onColorSelected(swatch.toHighlightArgb()) }
                    .semantics { contentDescription = "Highlight color" }
            )
        }
    }
}
