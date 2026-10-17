package com.application.bibleapp.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.navigation.Screen
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.utils.formatDisplayDate
import com.application.bibleapp.utils.formatVerseRefs
import com.application.bibleapp.viewmodel.BibleViewModel

/**
 * One Library card's full detail — the card preview truncates the verse text to keep the list
 * scannable, so this is where the whole thing actually gets read. [onOpenInBible] is the second
 * tap the roadmap called for: the reading view itself (arming selection/opening the note editor,
 * per [BibleViewModel.openHighlightFromLibrary]/[BibleViewModel.openNoteFromLibrary]) is one tap
 * away from here, not the first thing that happens when the card itself is tapped.
 *
 * Looks [localId] back up from [BibleViewModel.libraryHighlights]/[BibleViewModel.libraryNotes] —
 * already loaded by the Library screen this was reached from — rather than carrying the whole
 * [com.application.bibleapp.data.model.Highlight]/[com.application.bibleapp.data.model.Note]
 * through the nav graph.
 */
@Composable
fun LibraryItemDetailView(
    bibleViewModel: BibleViewModel,
    type: String,
    localId: Long,
    modifier: Modifier = Modifier,
    onOpenInBible: () -> Unit
) {
    val highlights by bibleViewModel.libraryHighlights.collectAsState()
    val notes by bibleViewModel.libraryNotes.collectAsState()
    val verseTextByKey by bibleViewModel.libraryVerseText.collectAsState()
    val bookNames by bibleViewModel.bookNames.collectAsState()
    val bookName = { bookId: Int -> bookNames[bookId] ?: BibleBooks.getBookById(bookId)?.name ?: "Unknown" }

    val highlight = if (type == Screen.LibraryItemDetail.TYPE_HIGHLIGHT) {
        highlights.firstOrNull { it.localId == localId }
    } else {
        null
    }
    val note = if (type == Screen.LibraryItemDetail.TYPE_NOTE) {
        notes.firstOrNull { it.localId == localId }
    } else {
        null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl)
    ) {
        when {
            highlight != null -> {
                val verseText = resolveVerseText(highlight.versionId, highlight.verses, verseTextByKey)
                Text(
                    text = formatVerseRefs(highlight.verses, bookName),
                    style = MaterialTheme.typography.headlineSmall
                )
                Row(
                    modifier = Modifier.padding(top = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(Color(highlight.color).copy(alpha = 1f))
                    )
                    Text(
                        text = "Highlighted " + formatDisplayDate(highlight.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.lg),
                    color = Color(highlight.color),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = verseText,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(Spacing.lg)
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.xl))
                OpenInBibleButton(onClick = onOpenInBible)
                TextButton(
                    onClick = { bibleViewModel.deleteLibraryHighlight(highlight) },
                    modifier = Modifier.padding(top = Spacing.sm)
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

            note != null -> {
                val verseText = resolveVerseText(note.versionId, note.verses, verseTextByKey)
                Text(
                    text = formatVerseRefs(note.verses, bookName),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Noted " + formatDisplayDate(note.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
                if (verseText.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.lg),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text(
                            text = verseText,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.Serif,
                                fontStyle = FontStyle.Italic
                            ),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(Spacing.lg)
                        )
                    }
                }
                Text(
                    text = note.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = Spacing.lg)
                )
                Spacer(modifier = Modifier.height(Spacing.xl))
                OpenInBibleButton(onClick = onOpenInBible)
                TextButton(
                    onClick = { bibleViewModel.deleteLibraryNote(note) },
                    modifier = Modifier.padding(top = Spacing.sm)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Delete note",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = Spacing.sm)
                    )
                }
            }

            else -> {
                // Deleted from another screen (or mid-sync) between opening the list and this
                // detail screen — nothing to show, so say so rather than rendering an empty page.
                Text(
                    text = "This item is no longer available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun OpenInBibleButton(onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text("Open in Bible")
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            modifier = Modifier
                .padding(start = Spacing.sm)
                .size(18.dp)
        )
    }
}
