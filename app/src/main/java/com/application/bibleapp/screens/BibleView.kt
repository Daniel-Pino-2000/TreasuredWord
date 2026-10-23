package com.application.bibleapp.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.application.bibleapp.components.BibleText
import com.application.bibleapp.components.HighlightActionSheet
import com.application.bibleapp.components.NoteEditorSheet
import com.application.bibleapp.components.NoteListSheet
import com.application.bibleapp.components.SignInNudgeBanner
import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.data.remote.VerseLocationDto
import com.application.bibleapp.ui.theme.ReadingStyle
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.utils.verseRefsText
import com.application.bibleapp.viewmodel.BibleViewModel
import com.application.bibleapp.R
import androidx.compose.ui.res.stringResource

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
    val noteListVerse by bibleViewModel.noteListVerse.collectAsState()
    val bookNames by bibleViewModel.bookNames.collectAsState()
    val showSignInNudge by bibleViewModel.showSignInNudge.collectAsState()
    val scrollRequest by bibleViewModel.scrollRequest.collectAsState()
    val chapterTitle = "$currentBookName $currentChapter"
    val bookName = { bookId: Int -> bookNames[bookId] ?: BibleBooks.getBookById(bookId)?.name ?: "Unknown" }

    // Flattened lookup for BibleText's per-verse background rendering — one entry per verse a
    // highlight covers, not per highlight. New overlaps can't be created (api_contract.md decision
    // 19), but one from another client/older sync resolves to the most recently created highlight,
    // matching which one BibleViewModel.onVerseTap opens.
    val highlightColorsByVerse = remember(highlightsInChapter) {
        buildMap<VerseLocationDto, Color> {
            highlightsInChapter.sortedBy { it.createdAt }.forEach { highlight ->
                val color = Color(highlight.color)
                highlight.verses.forEach { put(it, color) }
            }
        }
    }

    // How many active notes cover each verse in this chapter — drives BibleText's inline badge.
    val noteCountsByVerse = remember(notesInChapter) {
        notesInChapter.flatMap { it.verses }.groupingBy { it }.eachCount()
    }

    // This chapter's plain verse text by location — for the sheets' verse previews.
    val verseTextByLocation = remember(verses) {
        verses.mapNotNull { verse ->
            val bookId = verse.bookId ?: return@mapNotNull null
            val chapter = verse.chapter ?: return@mapNotNull null
            val number = verse.verse ?: return@mapNotNull null
            VerseLocationDto(bookId, chapter, number) to verse.text.removePrefix("¶ ")
        }.toMap()
    }

    // The banner takes its own space above the text rather than floating over it, where it hid
    // the chapter title and first verses (#61).
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AnimatedVisibility(
            visible = showSignInNudge,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            SignInNudgeBanner(
                onSignInClick = {
                    bibleViewModel.dismissSignInNudge()
                    onSignInClick()
                },
                onDismiss = { bibleViewModel.dismissSignInNudge() },
                modifier = Modifier.fillMaxWidth()
            )
        }

        BibleText(
            verses = verses,
            scrollToIndex = currentVerse,
            chapterTitle = chapterTitle,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            textScale = verseTextScale.multiplier,
            onFootnoteClick = { noteId -> bibleViewModel.selectFootnote(noteId) },
            highlightColorsByVerse = highlightColorsByVerse,
            selectedVerses = selectedVerses,
            onVerseTap = { location -> bibleViewModel.onVerseTap(location) },
            onVerseLongPress = { location -> bibleViewModel.onVerseLongPress(location) },
            noteCountsByVerse = noteCountsByVerse,
            onNoteGlyphTap = { location -> bibleViewModel.onNoteBadgeTap(location) },
            scrollRequest = scrollRequest,
            onTopVisibleVerseChange = { verse -> bibleViewModel.onTopVisibleVerseChange(verse) }
        )
    }

    selectedFootnote?.let { footnote ->
        ModalBottomSheet(
            onDismissRequest = { bibleViewModel.dismissFootnote() },
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = Spacing.xl, vertical = Spacing.sm)
                    .navigationBarsPadding()
            ) {
                Text(
                    text = stringResource(R.string.footnote_verse, footnote.verse),
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
        val notesOnSameVerses = notesInChapter.filter { note -> note.verses.any { it in highlight.verses } }
        HighlightActionSheet(
            highlight = highlight,
            reference = verseRefsText(highlight.verses, bookName),
            verseText = remember(highlight, verseTextByLocation) {
                versePreviewText(highlight.verses, verseTextByLocation)
            },
            notes = notesOnSameVerses,
            onColorSelected = { target, colorArgb -> bibleViewModel.recolorHighlight(target, colorArgb) },
            onRemove = { target -> bibleViewModel.removeHighlight(target) },
            onOpenNote = { note ->
                bibleViewModel.dismissHighlightPopup()
                bibleViewModel.openNoteEditorForNote(note)
            },
            onDismiss = { bibleViewModel.dismissHighlightPopup() }
        )
    }

    noteListVerse?.let { location ->
        val notesOnVerse = notesInChapter.filter { location in it.verses }
        NoteListSheet(
            title = verseRefsText(listOf(location), bookName),
            notes = notesOnVerse,
            onOpenNote = { note -> bibleViewModel.openNoteFromList(note) },
            onAddNote = { bibleViewModel.addNoteToVerse(location) },
            onDismiss = { bibleViewModel.dismissNoteList() }
        )
    }

    noteEditor?.let { editor ->
        val editorVerseText = remember(editor.verses, verseTextByLocation) {
            versePreviewText(editor.verses, verseTextByLocation)
        }
        NoteEditorSheet(
            verses = editor.verses,
            verseText = editorVerseText,
            text = editor.text,
            isEditingExisting = editor.existingNote != null,
            hasUnsavedChanges = editor.hasUnsavedChanges,
            bookName = bookName,
            onTextChange = { text -> bibleViewModel.updateNoteEditorText(text) },
            onRemoveVerse = { location -> bibleViewModel.removeVerseFromNoteEditor(location) },
            onSave = { bibleViewModel.saveNoteEditor() },
            onDelete = { bibleViewModel.deleteNoteEditor() },
            onDismiss = { bibleViewModel.dismissNoteEditor() }
        )
    }
}

/** The given verses' text in Bible order — verse numbers inline only when there's more than one,
 *  so a range still reads clearly. Verses not in [textByLocation] (another chapter) are skipped. */
private fun versePreviewText(verses: List<VerseLocationDto>, textByLocation: Map<VerseLocationDto, String>): String {
    val ordered = verses.sortedWith(compareBy({ it.bookId }, { it.chapter }, { it.verse }))
    return ordered.mapNotNull { location ->
        textByLocation[location]?.let { if (ordered.size > 1) "${location.verse} $it" else it }
    }.joinToString(" ")
}
