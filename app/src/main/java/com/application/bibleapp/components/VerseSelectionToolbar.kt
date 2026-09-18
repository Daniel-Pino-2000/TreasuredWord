package com.application.bibleapp.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.application.bibleapp.ui.theme.Spacing
import androidx.compose.ui.unit.dp

/**
 * Bottom-anchored contextual toolbar shown while one or more verses are selected (see
 * BibleViewModel.selectedVerses / docs/UI_Integration_Roadmap.md). Tapping a swatch immediately
 * creates the highlight and clears the selection — no separate confirm step. The Note action
 * opens NoteEditorSheet over the current selection instead (Copy/Share are still deferred).
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
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onAddNote) {
                        Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = "Add note")
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
