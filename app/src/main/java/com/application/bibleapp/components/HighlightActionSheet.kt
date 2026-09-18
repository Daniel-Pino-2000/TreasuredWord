package com.application.bibleapp.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.application.bibleapp.data.model.Highlight
import com.application.bibleapp.ui.theme.Spacing

/**
 * Shown when tapping (not long-pressing) an already-highlighted verse outside selection mode.
 * The only post-creation edits a highlight supports — recolor, or remove — since the verse list
 * is immutable after creation (server/docs/api_contract.md decision 14); there's no "edit verses"
 * option here on purpose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightActionSheet(
    highlight: Highlight,
    onColorSelected: (Highlight, Int) -> Unit,
    onRemove: (Highlight) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm)) {
            Text(
                text = "Highlight",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            HighlightColorSwatchRow(
                onColorSelected = { colorArgb -> onColorSelected(highlight, colorArgb) },
                modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.lg)
            )
            TextButton(onClick = { onRemove(highlight) }) {
                Text("Remove highlight", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
