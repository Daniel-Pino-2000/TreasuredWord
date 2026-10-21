package com.application.bibleapp.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AutoStories
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.application.bibleapp.components.SWATCH_NAMES
import com.application.bibleapp.ui.theme.HighlightColors
import com.application.bibleapp.ui.theme.toHighlightArgb
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import com.application.bibleapp.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.data.model.Highlight
import com.application.bibleapp.data.model.Note
import com.application.bibleapp.data.remote.VerseLocationDto
import com.application.bibleapp.navigation.Screen
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.utils.displayDate
import com.application.bibleapp.utils.verseRefsText
import com.application.bibleapp.viewmodel.BibleViewModel
import com.application.bibleapp.viewmodel.VerseTextKey
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource

private enum class LibraryTab(@StringRes val labelRes: Int, val routeArg: String) {
    HIGHLIGHTS(R.string.label_highlights, Screen.Library.TAB_HIGHLIGHTS),
    NOTES(R.string.label_notes, Screen.Library.TAB_NOTES);

    companion object {
        fun fromRouteArg(arg: String?): LibraryTab = entries.firstOrNull { it.routeArg == arg } ?: HIGHLIGHTS
    }
}

/**
 * A single screen with a segmented Highlights/Notes control, rather than two routes — see
 * docs/UI_Integration_Roadmap.md Phase D. Reads straight from [BibleViewModel.libraryHighlights]/
 * [BibleViewModel.libraryNotes] (loaded once via [BibleViewModel.loadLibrary] when this screen
 * opens); nothing here needs the backend, so it's fully usable offline and signed out, same as
 * the highlighting/notes UI it's listing. [initialTab] lets Settings' two "My Library" rows
 * (Phase G) deep-link straight to either tab instead of always opening on Highlights.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryView(
    bibleViewModel: BibleViewModel,
    modifier: Modifier = Modifier,
    initialTab: String? = null,
    onOpenDetail: (type: String, localId: Long) -> Unit
) {
    LaunchedEffect(Unit) { bibleViewModel.loadLibrary() }

    val highlights by bibleViewModel.libraryHighlights.collectAsState()
    val notes by bibleViewModel.libraryNotes.collectAsState()
    val verseTextByKey by bibleViewModel.libraryVerseText.collectAsState()
    val bookNames by bibleViewModel.bookNames.collectAsState()
    val bookName = { bookId: Int -> bookNames[bookId] ?: BibleBooks.getBookById(bookId)?.name ?: "Unknown" }

    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.fromRouteArg(initialTab)) }
    var query by rememberSaveable { mutableStateOf("") }
    // Stored highlight ARGBs to show; empty = every color. A List (not Set) so it's saveable.
    var colorFilter by rememberSaveable { mutableStateOf(emptyList<Int>()) }

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
                    Text(stringResource(tab.labelRes))
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = {
                Text(
                    stringResource(
                        if (selectedTab == LibraryTab.HIGHLIGHTS) R.string.library_search_by_book else R.string.library_search_notes
                    )
                )
            },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
        )

        when (selectedTab) {
            LibraryTab.HIGHLIGHTS -> {
                if (highlights.isNotEmpty()) {
                    HighlightColorFilterRow(
                        selected = colorFilter,
                        onToggle = { argb ->
                            colorFilter = if (argb in colorFilter) colorFilter - argb else colorFilter + argb
                        },
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
                    )
                }
                val filtered = remember(highlights, query, bookNames, colorFilter) {
                    highlights.filter { h ->
                        (colorFilter.isEmpty() || h.color in colorFilter) &&
                            (query.isBlank() || h.verses.any { bookName(it.bookId).contains(query, ignoreCase = true) })
                    }
                }
                if (filtered.isEmpty()) {
                    EmptyLibraryState(
                        message = when {
                            highlights.isEmpty() -> stringResource(R.string.library_empty_highlights)
                            query.isBlank() -> stringResource(R.string.library_no_highlights_in_colors)
                            else -> stringResource(R.string.library_no_highlights_match, query)
                        },
                        icon = rememberVectorPainter(Icons.Outlined.AutoStories)
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        items(filtered, key = { it.localId }) { highlight ->
                            SwipeToDeleteRow(onDelete = { bibleViewModel.deleteLibraryHighlight(highlight) }) {
                                HighlightCard(
                                    highlight = highlight,
                                    verseText = resolveVerseText(highlight.versionId, highlight.verses, verseTextByKey),
                                    bookName = bookName,
                                    onClick = {
                                        onOpenDetail(Screen.LibraryItemDetail.TYPE_HIGHLIGHT, highlight.localId)
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
                        message = if (notes.isEmpty()) stringResource(R.string.library_empty_notes)
                        else stringResource(R.string.library_no_notes_match, query),
                        icon = painterResource(R.drawable.ic_stylus_note_outline)
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        items(filtered, key = { it.localId }) { note ->
                            SwipeToDeleteRow(onDelete = { bibleViewModel.deleteLibraryNote(note) }) {
                                NoteCard(
                                    note = note,
                                    verseText = resolveVerseText(note.versionId, note.verses, verseTextByKey),
                                    bookName = bookName,
                                    onClick = {
                                        onOpenDetail(Screen.LibraryItemDetail.TYPE_NOTE, note.localId)
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
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.onError)
            }
        }
    ) {
        content()
    }
}

/** Joins one highlight/note's verse(s) into a single display string from the preloaded
 *  [textByKey] lookup (see [BibleViewModel.libraryVerseText]) — a bare number prefix per verse
 *  only when there's more than one, so a single-verse card (the overwhelming common case) reads
 *  as a clean quotation rather than "16 For God so loved...". */
internal fun resolveVerseText(versionId: String, verses: List<VerseLocationDto>, textByKey: Map<VerseTextKey, String>): String {
    val ordered = verses.sortedWith(compareBy({ it.chapter }, { it.verse }))
    if (ordered.size <= 1) {
        return ordered.firstOrNull()?.let { textByKey[VerseTextKey(versionId, it)] }.orEmpty()
    }
    return ordered.joinToString(" ") { location ->
        val text = textByKey[VerseTextKey(versionId, location)].orEmpty()
        "${location.verse} $text"
    }
}

@Composable
private fun HighlightCard(highlight: Highlight, verseText: String, bookName: (Int) -> String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium),
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // A bookmark-tab accent rather than a small legend dot — reads at a glance from
            // across the list, the way a real highlighter streak would down a book's margin.
            // Full opacity, unlike the translucent text-background rendering in BibleText: this
            // is a color swatch, not verse text to keep legible underneath.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(6.dp)
                    .background(Color(highlight.color).copy(alpha = 1f))
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(Spacing.md)
            ) {
                Text(
                    text = verseRefsText(highlight.verses, bookName),
                    style = MaterialTheme.typography.bodyLarge
                )
                if (verseText.isNotBlank()) {
                    Text(
                        text = verseText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                }
                Text(
                    text = displayDate(highlight.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
        }
    }
}

@Composable
private fun NoteCard(note: Note, verseText: String, bookName: (Int) -> String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium),
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_stylus_note),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = verseRefsText(note.verses, bookName),
                    style = MaterialTheme.typography.bodyLarge
                )
                if (verseText.isNotBlank()) {
                    Text(
                        text = verseText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                }
                Text(
                    text = note.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.sm)
                )
                Text(
                    text = displayDate(note.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
        }
    }
}

/** Multi-select color filter for the Highlights tab — one toggle per [HighlightColors.palette]
 *  swatch, matched against a highlight's stored (translucent) ARGB via [toHighlightArgb]. */
@Composable
private fun HighlightColorFilterRow(selected: List<Int>, onToggle: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.library_color_filter),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HighlightColors.palette.forEachIndexed { index, swatch ->
            val argb = swatch.toHighlightArgb()
            val name = stringResource(SWATCH_NAMES.getOrElse(index) { R.string.highlight_color_generic })
            val filterLabel = stringResource(R.string.library_show_color_highlights, name)
            val isSelected = argb in selected
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .then(
                        if (isSelected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        else Modifier
                    )
                    .toggleable(value = isSelected, role = Role.Checkbox, onValueChange = { onToggle(argb) })
                    .semantics { contentDescription = filterLabel },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryState(message: String, icon: Painter) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
