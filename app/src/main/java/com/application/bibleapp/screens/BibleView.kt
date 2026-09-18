package com.application.bibleapp.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.application.bibleapp.components.BibleText
import com.application.bibleapp.components.HighlightActionSheet
import com.application.bibleapp.components.NoteEditorSheet
import com.application.bibleapp.components.SignInNudgeBanner
import com.application.bibleapp.components.VerseSelectionToolbar
import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.data.remote.VerseLocationDto
import com.application.bibleapp.ui.theme.ReadingStyle
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.viewmodel.BibleViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BibleView(
    bibleViewModel: BibleViewModel,
    modifier: Modifier = Modifier,
    onSignInClick: () -> Unit = {}
) {

    val currentVerse by bibleViewModel.currentVerse.collectAsState()
    val currentBookName by bibleViewModel.currentBookName.collectAsState()
    val currentChapter by bibleViewModel.currentChapter.collectAsState()
    val verses by bibleViewModel.verses.collectAsState()
    val selectedFootnote by bibleViewModel.selectedFootnote.collectAsState()
    val verseTextScale by bibleViewModel.verseTextScale.collectAsState()
    val highlightsInChapter by bibleViewModel.highlightsInChapter.collectAsState()
    val selectedVerses by bibleViewModel.selectedVerses.collectAsState()
    val highlightPopup by bibleViewModel.highlightPopup.collectAsState()
    val notesInChapter by bibleViewModel.notesInChapter.collectAsState()
    val noteEditor by bibleViewModel.noteEditor.collectAsState()
    val bookNames by bibleViewModel.bookNames.collectAsState()
    val showSignInNudge by bibleViewModel.showSignInNudge.collectAsState()
    val chapterTitle = "$currentBookName $currentChapter"
    val bookName = { bookId: Int -> bookNames[bookId] ?: BibleBooks.getBookById(bookId)?.name ?: "Unknown" }

    // Flattened lookup for BibleText's per-verse background rendering — one entry per verse a
    // highlight covers, not per highlight, so a verse covered by more than one overlapping
    // highlight just resolves to whichever was processed last (last-write-wins, same as the rest
    // of this feature's conflict handling; overlapping highlights aren't disallowed by the API).
    val highlightColorsByVerse = remember(highlightsInChapter) {
        buildMap<VerseLocationDto, Color> {
            highlightsInChapter.forEach { highlight ->
                val color = Color(highlight.color)
                highlight.verses.forEach { put(it, color) }
            }
        }
    }

    // Every verse covered by an active note in this chapter — drives BibleText's inline glyph.
    val notedVerses = remember(notesInChapter) {
        notesInChapter.flatMapTo(mutableSetOf()) { it.verses }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        BibleText(
            verses = verses,
            scrollToIndex = currentVerse,
            chapterTitle = chapterTitle,
            modifier = Modifier.fillMaxSize(),
            textScale = verseTextScale.multiplier,
            onFootnoteClick = { noteId -> bibleViewModel.selectFootnote(noteId) },
            highlightColorsByVerse = highlightColorsByVerse,
            selectedVerses = selectedVerses,
            onVerseTap = { location -> bibleViewModel.onVerseTap(location) },
            onVerseLongPress = { location -> bibleViewModel.onVerseLongPress(location) },
            notedVerses = notedVerses,
            onNoteGlyphTap = { location ->
                notesInChapter.firstOrNull { location in it.verses }
                    ?.let { bibleViewModel.openNoteEditorForNote(it) }
            }
        )

        if (selectedVerses.isNotEmpty()) {
            VerseSelectionToolbar(
                selectionCount = selectedVerses.size,
                onColorSelected = { colorArgb -> bibleViewModel.highlightSelection(colorArgb) },
                onAddNote = { bibleViewModel.openNoteEditorForSelection() },
                onCancel = { bibleViewModel.clearSelection() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            )
        }

        if (showSignInNudge) {
            SignInNudgeBanner(
                onSignInClick = {
                    bibleViewModel.dismissSignInNudge()
                    onSignInClick()
                },
                onDismiss = { bibleViewModel.dismissSignInNudge() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            )
        }
    }

    selectedFootnote?.let { footnote ->
        ModalBottomSheet(
            onDismissRequest = { bibleViewModel.dismissFootnote() },
            shape = MaterialTheme.shapes.large
        ) {
            Column(modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm)) {
                Text(
                    text = "Verse ${footnote.verse}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = footnote.text,
                    style = ReadingStyle.FootnoteBody,
                    modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xl),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    highlightPopup?.let { highlight ->
        HighlightActionSheet(
            highlight = highlight,
            onColorSelected = { target, colorArgb -> bibleViewModel.recolorHighlight(target, colorArgb) },
            onRemove = { target -> bibleViewModel.removeHighlight(target) },
            onDismiss = { bibleViewModel.dismissHighlightPopup() }
        )
    }

    noteEditor?.let { editor ->
        NoteEditorSheet(
            verses = editor.verses,
            text = editor.text,
            isEditingExisting = editor.existingNote != null,
            bookName = bookName,
            onTextChange = { text -> bibleViewModel.updateNoteEditorText(text) },
            onRemoveVerse = { location -> bibleViewModel.removeVerseFromNoteEditor(location) },
            onSave = { bibleViewModel.saveNoteEditor() },
            onDelete = { bibleViewModel.deleteNoteEditor() },
            onDismiss = { bibleViewModel.dismissNoteEditor() }
        )
    }
}
