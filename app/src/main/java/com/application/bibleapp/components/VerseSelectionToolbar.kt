package com.application.bibleapp.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.application.bibleapp.R
import com.application.bibleapp.ui.theme.Spacing
import androidx.compose.ui.unit.dp

/**
 * A genuinely floating card — margin on every side, all four corners rounded, a real cast
 * shadow — positioned by MainActivity a fixed gap above [MainBottomBar]'s current top edge,
 * never inside its own slot or flush against it: it should read as "hovering over" the bottom
 * chrome, not "docked onto" or "replacing" it. MainActivity recomputes that gap continuously off
 * [MainBottomBar]'s live measured height, so this stays correctly positioned whether the tab row
 * is fully shown, mid-collapse, or fully hidden on scroll.
 *
 * Picking a color applies it and leaves the selection (and this toolbar) open, so a note can
 * still be added to the same verses; only Cancel or the Note action end it.
 */
@Composable
fun VerseSelectionToolbar(
    selectionCount: Int,
    onColorSelected: (Int) -> Unit,
    onAddNote: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectionCount == 1) "1 verse selected" else "$selectionCount verses selected",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    // A labeled filled-tonal chip, not another plain icon button — the one
                    // action here that leaves this toolbar (into the note editor) needs to read
                    // as the clear primary action, not blend in next to Cancel.
                    FilledTonalButton(
                        onClick = onAddNote,
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_stylus_note),
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Note")
                    }
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                    }
                }
            }
            HighlightColorSwatchRow(
                onColorSelected = onColorSelected,
                modifier = Modifier.padding(top = Spacing.sm)
            )
        }
    }
}
