package com.application.bibleapp.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.application.bibleapp.data.remote.VerseLocationDto
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorSheet(
    verses: List<VerseLocationDto>,
    text: String,
    isEditingExisting: Boolean,
    bookName: (Int) -> String,
    onTextChange: (String) -> Unit,
    onRemoveVerse: (VerseLocationDto) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm)) {
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
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                label = { Text("Note") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.lg, bottom = Spacing.xl),
                horizontalArrangement = if (isEditingExisting) Arrangement.SpaceBetween else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isEditingExisting) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onSave, enabled = text.isNotBlank()) {
                    Text("Save")
                }
            }
        }
    }
}
