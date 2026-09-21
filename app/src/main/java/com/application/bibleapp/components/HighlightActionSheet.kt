package com.application.bibleapp.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
                selectedColorArgb = highlight.color,
                modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.lg)
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(
                onClick = { onRemove(highlight) },
                modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xl)
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Remove highlight",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = Spacing.sm)
                )
            }
        }
    }
}
