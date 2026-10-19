package com.application.bibleapp.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.application.bibleapp.data.model.Highlight
import com.application.bibleapp.data.model.Note
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.isCompactHeight

/**
 * Shown when tapping (not long-pressing) an already-highlighted verse outside selection mode.
 * The only post-creation edits a highlight supports — recolor, or remove — since the verse list
 * is immutable after creation (server/docs/api_contract.md decision 14); there's no "edit verses"
 * option here on purpose.
 *
 * [notes] — every note on any of the same verses — each surface a preview + link — previously the
 * only way to reach an existing note from the reading view was its tiny inline glyph, so tapping
 * a highlighted-and-noted verse looked like it had nothing to say about the note at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightActionSheet(
    highlight: Highlight,
    /** e.g. "Romans 8:28-30" — names what this sheet acts on instead of a generic title. */
    reference: String,
    /** Preview of the highlighted verses; blank hides it. */
    verseText: String,
    notes: List<Note>,
    onColorSelected: (Highlight, Int) -> Unit,
    onRemove: (Highlight) -> Unit,
    onOpenNote: (Note) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.large,
        // Only matters once the sheet is as tall as the window (landscape): stop below the
        // status bar instead of running the drag handle up under it.
        modifier = Modifier.statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = Spacing.xl, vertical = Spacing.sm)
                .navigationBarsPadding()
        ) {
            Text(
                text = reference,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            // Preview, swatches and notes scroll in whatever room is left, so "Remove highlight"
            // stays on screen in landscape even when the verses carry several notes.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                if (verseText.isNotBlank()) {
                    Text(
                        text = verseText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                }
                HighlightColorSwatchRow(
                    onColorSelected = { colorArgb -> onColorSelected(highlight, colorArgb) },
                    selectedColorArgb = highlight.color,
                    modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.lg)
                )
                // Every note on these verses, not just the first — a verse can carry several.
                notes.forEach { note ->
                    NotePreviewRow(note = note, onClick = { onOpenNote(note) })
                    Spacer(modifier = Modifier.height(Spacing.sm))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(
                onClick = { onRemove(highlight) },
                modifier = Modifier.padding(top = Spacing.sm, bottom = if (isCompactHeight()) Spacing.sm else Spacing.xl)
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
