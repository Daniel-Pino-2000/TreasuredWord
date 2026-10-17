package com.application.bibleapp.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import com.application.bibleapp.data.remote.VerseLocationDto
import kotlinx.coroutines.launch
import com.application.bibleapp.ui.theme.Spacing

/**
 * Create/edit sheet for a note — reached either from [VerseSelectionToolbar]'s Note action (a
 * new note over the current selection) or from a saved note's inline glyph in the reading view
 * (BibleText's note-glyph tap). Both the verse list and text are editable in place, per
 * server/docs/api_contract.md decision 14 — unlike a highlight, a note has no "immutable after
 * creation" restriction on which verses it covers.
 *
 * Each chip's whole tap area removes that verse (Material3's `InputChip` has one click slot, not
 * a separately-clickable trailing icon) — the trailing X is shown only as an affordance, and only
 * while more than one verse remains, since a note must cover at least one (contract decision 17).
 *
 * Opens fully expanded — the default half-expanded state left Save/Delete below the screen edge.
 * With [hasUnsavedChanges], any dismiss (swipe down, scrim tap, back) asks "Discard note?" first
 * instead of silently throwing the typed text away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorSheet(
    verses: List<VerseLocationDto>,
    /** The covered verses' scripture text, so the note isn't written blind with the sheet
     *  covering the page — blank when it isn't loaded (e.g. a verse from another chapter). */
    verseText: String,
    text: String,
    isEditingExisting: Boolean,
    hasUnsavedChanges: Boolean,
    bookName: (Int) -> String,
    onTextChange: (String) -> Unit,
    onRemoveVerse: (VerseLocationDto) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val latestHasUnsavedChanges by rememberUpdatedState(hasUnsavedChanges)
    var showDiscardDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // Blocks swipe-to-dismiss while there's something to lose; the dialog takes over.
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && latestHasUnsavedChanges) {
                showDiscardDialog = true
                false
            } else {
                true
            }
        }
    )
    val scope = rememberCoroutineScope()

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard note?") },
            text = { Text("Your changes to this note haven't been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onDismiss()
                }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    // Back/scrim dismissals can already have animated the sheet away before
                    // reaching onDismissRequest — bring it back up for "keep editing".
                    scope.launch { sheetState.show() }
                }) {
                    Text("Keep editing")
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (latestHasUnsavedChanges) showDiscardDialog = true else onDismiss()
        },
        sheetState = sheetState,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = Spacing.xl, vertical = Spacing.sm)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Text(
                text = "Note",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.md)
            ) {
                items(verses, key = { "${it.bookId}-${it.chapter}-${it.verse}" }) { location ->
                    val removable = verses.size > 1
                    InputChip(
                        selected = false,
                        onClick = { if (removable) onRemoveVerse(location) },
                        label = { Text("${bookName(location.bookId)} ${location.chapter}:${location.verse}") },
                        trailingIcon = if (removable) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove verse",
                                    modifier = Modifier.size(InputChipDefaults.IconSize)
                                )
                            }
                        } else null
                    )
                }
            }
            if (verseText.isNotBlank()) {
                var expanded by remember(verseText) { mutableStateOf(false) }
                var overflows by remember(verseText) { mutableStateOf(false) }
                Text(
                    text = verseText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layout -> if (!expanded) overflows = layout.hasVisualOverflow },
                    modifier = Modifier
                        .padding(bottom = Spacing.md)
                        .then(
                            if (overflows || expanded) Modifier.clickable { expanded = !expanded }
                            else Modifier
                        )
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                label = { Text("Note") },
                placeholder = { Text("Write your thoughts on this verse…") },
                minLines = 4,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(top = Spacing.lg)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md, bottom = Spacing.xl),
                horizontalArrangement = if (isEditingExisting) Arrangement.SpaceBetween else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isEditingExisting) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                FilledTonalButton(onClick = onSave, enabled = text.isNotBlank()) {
                    Text("Save")
                }
            }
        }
    }
}
