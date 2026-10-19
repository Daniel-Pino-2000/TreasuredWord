package com.application.bibleapp.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.application.bibleapp.components.ChapterGrid
import com.application.bibleapp.components.SearchBar
import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.data.model.chapterCount
import com.application.bibleapp.data.model.oldTestamentBooks
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.viewmodel.BibleViewModel


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookPickerView(
    bibleViewModel: BibleViewModel,
    modifier: Modifier = Modifier,
    onBackClick: () -> Unit,
    onChapterClick: (Int, Int) -> Unit
) {

    val currentBook by bibleViewModel.currentBook.collectAsState()
    val currentChapter by bibleViewModel.currentChapter.collectAsState()
    val bookNames by bibleViewModel.bookNames.collectAsState()
    val chapterStructure by bibleViewModel.chapterStructure.collectAsState()
    val expandedBookId = rememberSaveable { mutableStateOf<Int?>(null) }

    var searchText by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current


    Column(
        modifier = modifier.fillMaxSize()
    ) {
        SearchBar(
            searchText,
            onTextChange = { newText ->
                searchText = newText
            },
            onSearchClick = {
                bibleViewModel.onSearchButtonClick()
            },
            placeholder = "Search Book"
        )

        val filteredBooks = BibleBooks.allBooks.filter {
            (bookNames[it.id] ?: it.name).contains(searchText, ignoreCase = true)
        }

        // Open the list already scrolled to the book being read, instead of
        // animating to it after the first (top-of-list) frame is visible —
        // that animation is what caused the visible "jump" before the user
        // could interact with the screen. initialFirstVisibleItemIndex is
        // read once, when the state is created, so this only positions the
        // list on entry and never fights the user's own scrolling.
        val initialBookIndex = remember {
            BibleBooks.allBooks.indexOfFirst { it.id == currentBook }.coerceAtLeast(0)
        }
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialBookIndex)

        // The last book in the list (Revelation, normally) has no list content
        // below it to push down and make room when its grid expands, so the grid
        // would otherwise render entirely below the viewport with nothing on
        // screen to show it happened. Scrolling just that book into view — with
        // the same animated scroll LazyColumn already uses elsewhere — fixes it
        // without touching how every other book expands.
        LaunchedEffect(expandedBookId.value) {
            val expandedId = expandedBookId.value ?: return@LaunchedEffect
            val index = filteredBooks.indexOfFirst { it.id == expandedId }
            if (index == filteredBooks.lastIndex) {
                listState.animateScrollToItem(index)
            }
        }

        LaunchedEffect(currentBook) {
            val index = filteredBooks.indexOfFirst { it.id == currentBook }
            listState.animateScrollToItem(index)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            items(filteredBooks) { book ->
                val isExpanded = expandedBookId.value == book.id

                Text(
                    text = bookNames[book.id] ?: book.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(true, onClick = {
                            expandedBookId.value =
                                if (expandedBookId.value == book.id) null else book.id
                            // Picking a book (usually right after searching for it) is the end
                            // of typing — otherwise the keyboard stays up over the chapter grid.
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        })
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                )

                if (isExpanded) {
                    ChapterGrid(
                        chapterCount = chapterStructure.chapterCount(book.id),
                        selectedChapter = if (book.id == currentBook) currentChapter else null
                    ) { chapterNumber ->

                        // Trigger the callback to navigate to VersePickerView
                        onChapterClick(book.id, chapterNumber)

                    }
                }

                // Every row ends with a divider; at the Old/New Testament boundary
                // this swaps in a marked version instead of stacking a second one
                // underneath the plain hairline — one deliberate mark at the seam,
                // at the same spacing as every other row's divider.
                if (book.id == oldTestamentBooks.last().id) {
                    TestamentDivider()
                } else {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

        }

    }
}

/**
 * Marks the Old/New Testament boundary in the book list: the same hairline
 * every row ends with, split around a small primary-colored dot. No extra
 * vertical padding beyond the plain divider it replaces, so the gap around
 * it reads the same as the gap around every other book name.
 */
@Composable
private fun TestamentDivider() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        Box(
            modifier = Modifier
                .padding(horizontal = Spacing.sm)
                .size(5.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}
