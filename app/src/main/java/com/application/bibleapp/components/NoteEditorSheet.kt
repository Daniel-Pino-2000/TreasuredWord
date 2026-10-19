package com.application.bibleapp.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
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
import androidx.compose.ui.unit.dp
import com.application.bibleapp.data.remote.VerseLocationDto
import kotlinx.coroutines.launch
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.isCompactHeight

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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val compactHeight = isCompactHeight()
    // A landscape phone with the soft keyboard up leaves the sheet ~130dp: room for the title
    // row and a one-line field, nothing else (#23).
    val typingInCompactHeight = compactHeight && WindowInsets.isImeVisible

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

    // Verse chips sit under the title in portrait; in compact height they share the title's row
    // so the note field itself still fits on screen above the pinned buttons.
    val verseChips: @Composable (Modifier) -> Unit = { modifier ->
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = modifier
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
    }

    val actions: @Composable () -> Unit = {
        if (isEditingExisting) {
            TextButton(onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
        FilledTonalButton(onClick = onSave, enabled = text.isNotBlank()) {
            Text("Save")
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (latestHasUnsavedChanges) showDiscardDialog = true else onDismiss()
        },
        sheetState = sheetState,
        shape = MaterialTheme.shapes.large,
        // Only matters once the sheet is as tall as the window (landscape): stop below the
        // status bar instead of running the drag handle up under it.
        modifier = Modifier.statusBarsPadding(),
        // Every dp counts in landscape; the sheet still drags/swipes without the handle.
        dragHandle = if (compactHeight) null else ({ BottomSheetDefaults.DragHandle() })
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = Spacing.xl, vertical = Spacing.sm)
                .navigationBarsPadding()
                .imePadding()
        ) {
            // In compact height Delete/Save share the title row too — a separate button row took
            // all the room the keyboard leaves, squeezing the note field out entirely.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Note",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (compactHeight) {
                    verseChips(Modifier.weight(1f).padding(start = Spacing.lg, end = Spacing.sm))
                    actions()
                }
            }
            // Everything below the title (and above the portrait button row) scrolls, and only
            // takes the room that's left — so Save/Delete stay pinned on screen even when the
            // whole sheet is taller than the window. While typing in compact height the field
            // alone takes that room instead: this column doesn't follow the cursor as the field
            // grows, but a height-capped field scrolls its own text to keep the cursor in view.
            // Only modifiers switch here, so the field keeps its focus (and the keyboard).
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .then(if (typingInCompactHeight) Modifier else Modifier.verticalScroll(scrollState))
            ) {
                if (!compactHeight) verseChips(Modifier.padding(top = Spacing.md, bottom = Spacing.md))
                if (verseText.isNotBlank() && !typingInCompactHeight) {
                    var expanded by remember(verseText) { mutableStateOf(false) }
                    var overflows by remember(verseText) { mutableStateOf(false) }
                    Text(
                        text = verseText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else if (compactHeight) 1 else 4,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { layout -> if (!expanded) overflows = layout.hasVisualOverflow },
                        modifier = Modifier
                            .padding(top = if (compactHeight) Spacing.sm else 0.dp, bottom = Spacing.md)
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
                    minLines = when {
                        !compactHeight -> 4
                        typingInCompactHeight -> 1
                        else -> 2
                    },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (!compactHeight) {
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
                    actions()
                }
            }
        }
    }
}
