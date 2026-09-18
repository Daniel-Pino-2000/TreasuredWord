package com.application.bibleapp.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.data.model.Highlight
import com.application.bibleapp.data.model.Note
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.utils.formatDisplayDate
import com.application.bibleapp.utils.formatVerseRefs
import com.application.bibleapp.viewmodel.BibleViewModel

private enum class LibraryTab(val label: String) {
    HIGHLIGHTS("Highlights"),
    NOTES("Notes")
}

/**
 * A single screen with a segmented Highlights/Notes control, rather than two routes — see
 * docs/UI_Integration_Roadmap.md Phase D. Reads straight from [BibleViewModel.libraryHighlights]/
 * [BibleViewModel.libraryNotes] (loaded once via [BibleViewModel.loadLibrary] when this screen
 * opens); nothing here needs the backend, so it's fully usable offline and signed out, same as
 * the highlighting/notes UI it's listing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryView(
    bibleViewModel: BibleViewModel,
    modifier: Modifier = Modifier,
    onVerseClick: (bookId: Int, chapter: Int, verse: Int) -> Unit
) {
    LaunchedEffect(Unit) { bibleViewModel.loadLibrary() }

    val highlights by bibleViewModel.libraryHighlights.collectAsState()
    val notes by bibleViewModel.libraryNotes.collectAsState()
    val bookNames by bibleViewModel.bookNames.collectAsState()
    val bookName = { bookId: Int -> bookNames[bookId] ?: BibleBooks.getBookById(bookId)?.name ?: "Unknown" }

    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.HIGHLIGHTS) }
    var query by rememberSaveable { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
        ) {
            LibraryTab.entries.forEachIndexed { index, tab ->
                SegmentedButton(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = LibraryTab.entries.size)
                ) {
                    Text(tab.label)
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(if (selectedTab == LibraryTab.HIGHLIGHTS) "Search by book" else "Search notes") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
        )

        when (selectedTab) {
            LibraryTab.HIGHLIGHTS -> {
                val filtered = remember(highlights, query, bookNames) {
                    if (query.isBlank()) highlights
                    else highlights.filter { h -> h.verses.any { bookName(it.bookId).contains(query, ignoreCase = true) } }
                }
                if (filtered.isEmpty()) {
                    EmptyLibraryState(
                        if (highlights.isEmpty()) "No highlights yet — long-press a verse while reading to add one."
                        else "No highlights match \"$query\"."
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        items(filtered, key = { it.localId }) { highlight ->
                            SwipeToDeleteRow(onDelete = { bibleViewModel.deleteLibraryHighlight(highlight) }) {
                                HighlightCard(
                                    highlight = highlight,
                                    bookName = bookName,
                                    onClick = {
                                        val first = highlight.verses.minWithOrNull(
                                            compareBy({ it.bookId }, { it.chapter }, { it.verse })
                                        ) ?: return@HighlightCard
                                        onVerseClick(first.bookId, first.chapter, first.verse)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            LibraryTab.NOTES -> {
                val filtered = remember(notes, query, bookNames) {
                    if (query.isBlank()) notes
                    else notes.filter { n ->
                        n.text.contains(query, ignoreCase = true) ||
                            n.verses.any { bookName(it.bookId).contains(query, ignoreCase = true) }
                    }
                }
                if (filtered.isEmpty()) {
                    EmptyLibraryState(
                        if (notes.isEmpty()) "No notes yet — select a verse while reading to add one."
                        else "No notes match \"$query\"."
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        items(filtered, key = { it.localId }) { note ->
                            SwipeToDeleteRow(onDelete = { bibleViewModel.deleteLibraryNote(note) }) {
                                NoteCard(
                                    note = note,
                                    bookName = bookName,
                                    onClick = {
                                        val first = note.verses.minWithOrNull(
                                            compareBy({ it.bookId }, { it.chapter }, { it.verse })
                                        ) ?: return@NoteCard
                                        onVerseClick(first.bookId, first.chapter, first.verse)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Swipe-left (end-to-start) to delete — [enableDismissFromStartToEnd] off so an accidental
 *  right-swipe (e.g. a back-navigation gesture near the screen edge) can't trigger it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error, MaterialTheme.shapes.medium)
                    .padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onError)
            }
        }
    ) {
        content()
    }
}

@Composable
private fun HighlightCard(highlight: Highlight, bookName: (Int) -> String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium),
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    // Full opacity here, unlike the translucent text-background rendering in
                    // BibleText — this dot is a color legend, not verse text to keep legible
                    // underneath, so it should read as distinctly as the picker's own swatches.
                    .background(Color(highlight.color).copy(alpha = 1f))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatVerseRefs(highlight.verses, bookName),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = formatDisplayDate(highlight.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NoteCard(note: Note, bookName: (Int) -> String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium),
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.StickyNote2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatVerseRefs(note.verses, bookName),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = note.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
                Text(
                    text = formatDisplayDate(note.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
        }
    }
}

@Composable
private fun EmptyLibraryState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
