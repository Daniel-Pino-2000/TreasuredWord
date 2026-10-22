package com.application.bibleapp.screens


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.application.bibleapp.components.SearchBar
import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.data.model.VerseUI
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.viewmodel.BibleViewModel
import androidx.compose.foundation.lazy.items
import com.application.bibleapp.R
import androidx.compose.ui.res.stringResource

/** A handful of common topics to search for before the user has typed anything themselves. */
private val SUGGESTED_SEARCHES = listOf("love", "faith", "hope", "grace", "peace", "joy", "forgiveness")

@Composable
fun SearchView(
    bibleViewModel: BibleViewModel,
    modifier: Modifier = Modifier,
    onVerseClicked: (bookId: Int, chapter: Int, verse: Int) -> Unit
) {

    val searchText by bibleViewModel.searchQuery.collectAsState()
    val searchState by bibleViewModel.searchState.collectAsState()
    val bookNames by bibleViewModel.bookNames.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        SearchBar(
            searchText = searchText,
            onTextChange = { bibleViewModel.onSearchQueryChange(it) },
            onSearch = { bibleViewModel.onSearchSubmit() },
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.search_verse_placeholder)
        )

        // Always reserves its height so results don't jump when a search starts or ends.
        Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
            if (searchState.isSearching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // Driven only by searchState (what was actually searched), never by the live text.
        when {
            searchState.results.isNotEmpty() ->
                SearchResultsList(searchState.query, searchState.results, bookNames, onVerseClicked)
            searchState.isSearching -> Unit
            searchState.hasError -> SearchErrorState(onRetry = { bibleViewModel.onSearchSubmit() })
            searchState.query.isEmpty() -> SearchEmptyState(
                onSuggestionClick = { keyword ->
                    bibleViewModel.onSearchQueryChange(keyword)
                    bibleViewModel.onSearchSubmit()
                }
            )
            else -> SearchNoResultsState(query = searchState.query)
        }
    }
}

@Composable
private fun SearchEmptyState(onSuggestionClick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.search_empty_prompt),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.xl)
        )
        Text(
            text = stringResource(R.string.search_suggestions_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = Spacing.sm)
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SUGGESTED_SEARCHES.forEach { keyword ->
                SuggestionChip(
                    onClick = { onSuggestionClick(keyword) },
                    label = { Text(keyword) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}

@Composable
private fun SearchNoResultsState(query: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.SearchOff,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.search_no_results, query),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.lg)
        )
    }
}

@Composable
private fun SearchErrorState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.search_error),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.md)
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun SearchResultsList(
    query: String,
    searchedVerses: List<VerseUI>,
    bookNames: Map<Int, String>,
    onVerseClicked: (bookId: Int, chapter: Int, verse: Int) -> Unit
) {
    // A fresh scroll position per query: new results start at the top, while Back from a verse
    // still restores where the user was in these results.
    key(query) {
        val listState = rememberLazyListState()
        val keyboardController = LocalSoftwareKeyboardController.current
        LaunchedEffect(listState.isScrollInProgress) {
            if (listState.isScrollInProgress) keyboardController?.hide()
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
        ) {
            // GROUP BY book/chapter/verse makes this unique, unlike VerseUI.id's string hash.
            items(searchedVerses, key = { "${it.bookId}_${it.chapter}_${it.verse}" }) { verse ->
                val bookName = bookNames[verse.bookId ?: 0] ?: BibleBooks.getBookById(verse.bookId ?: 0)?.name ?: "Unknown"

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onVerseClicked(verse.bookId!!, verse.chapter!!, verse.verse!!)
                        }
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                ) {
                    Text(
                        text = "$bookName ${verse.chapter}:${verse.verse}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = verse.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
