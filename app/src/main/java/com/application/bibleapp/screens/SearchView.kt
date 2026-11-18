package com.application.bibleapp.screens


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.verticalScroll
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
import com.application.bibleapp.components.AiCrisisCard
import com.application.bibleapp.components.FIND_A_HELPLINE_URL
import com.application.bibleapp.components.SearchBar
import com.application.bibleapp.utils.rememberExternalLinks
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.data.model.VerseUI
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.viewmodel.BibleViewModel
import androidx.compose.foundation.lazy.items
import com.application.bibleapp.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.application.bibleapp.components.ServerWakeHint
import com.application.bibleapp.components.rememberWaitingLong
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.application.bibleapp.components.passageText
import com.application.bibleapp.utils.formatVerseReference
import com.application.bibleapp.viewmodel.KeyPassage
import com.application.bibleapp.viewmodel.MeaningSearchState
import com.application.bibleapp.viewmodel.MeaningSearchStatus

@Composable
fun SearchView(
    bibleViewModel: BibleViewModel,
    modifier: Modifier = Modifier,
    onVerseClicked: (bookId: Int, chapter: Int, verse: Int) -> Unit
) {

    val searchText by bibleViewModel.searchQuery.collectAsState()
    val searchState by bibleViewModel.searchState.collectAsState()
    val bookNames by bibleViewModel.bookNames.collectAsState()
    val suggestions by bibleViewModel.searchSuggestions.collectAsState()
    val meaningState by bibleViewModel.meaningSearchState.collectAsState()
    val selectedVersion by bibleViewModel.selectedVersion.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val links = rememberExternalLinks { bibleViewModel.showMessage(it) }

    // Not for a verse reference or a quoted phrase (#287): those keep the plain list of results.
    val offersMeaning = remember(searchState.query, bookNames) {
        searchState.query.isNotEmpty() && bibleViewModel.offersMeaningSearch(searchState.query)
    }

    // The window resizes for the keyboard (manifest): only the results get shorter (#228).
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
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
            offersMeaning -> SearchResultsList(
                query = searchState.query,
                searchedVerses = searchState.results,
                bookNames = bookNames,
                onVerseClicked = onVerseClicked,
                meaning = MeaningSection(
                    // A section for another query (typing moved on) offers to search this one.
                    state = meaningState.takeIf { it.query == searchState.query } ?: MeaningSearchState(),
                    wordsSearching = searchState.isSearching,
                    wordsFailed = searchState.hasError,
                    selectedVersionId = selectedVersion.id,
                    versionLabel = bibleViewModel::versionLabel,
                    onSearch = {
                        keyboardController?.hide()
                        bibleViewModel.onSearchSubmit()
                    },
                    onFindHelpline = { links.openPage(FIND_A_HELPLINE_URL) }
                )
            )
            searchState.results.isNotEmpty() ->
                SearchResultsList(searchState.query, searchState.results, bookNames, onVerseClicked)
            searchState.isSearching -> Unit
            searchState.hasError -> SearchErrorState(onRetry = { bibleViewModel.onSearchSubmit() })
            searchState.query.isEmpty() -> SearchEmptyState(
                suggestions = suggestions,
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
private fun SearchEmptyState(suggestions: List<String>, onSuggestionClick: (String) -> Unit) {
    // Centered when it fits, scrollable when it doesn't: in landscape the suggestions were
    // pushed below the screen with no way to reach them (#88).
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
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
            // None when the version's language has no list, or none of its words find anything (#60, #88).
            if (suggestions.isEmpty()) return@Column
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
                suggestions.forEach { keyword ->
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

/** What the "Related by meaning" part of the results needs (#287). [onSearch] submits the search:
 *  the section's own button, Retry, and Retry for the word search all do the same. */
private class MeaningSection(
    val state: MeaningSearchState,
    val wordsSearching: Boolean,
    val wordsFailed: Boolean,
    val selectedVersionId: String,
    val versionLabel: (String) -> String,
    val onSearch: () -> Unit,
    val onFindHelpline: () -> Unit
)

/**
 * The results. With [meaning], two sections (#287): "Verses with these words" (the word search,
 * instant and offline) first, then "Related by meaning" from the server, whose loading row sits
 * below the word results so they never wait for it or move. When the query matched a topic guide,
 * its "Key passages" come between them, with the server's answer (#320). When that guide is the
 * crisis guide, the Bible Helper's help card and then the key passages come first of all (#324);
 * offline or unavailable, the card alone when the phone's own crisis phrases match (#330).
 * Without [meaning] (a verse reference), the word results alone.
 */
@Composable
private fun SearchResultsList(
    query: String,
    searchedVerses: List<VerseUI>,
    bookNames: Map<Int, String>,
    onVerseClicked: (bookId: Int, chapter: Int, verse: Int) -> Unit,
    meaning: MeaningSection? = null
) {
    // A fresh scroll position per query: new results start at the top, while Back from a verse
    // still restores where the user was in these results.
    key(query) {
        val listState = rememberLazyListState()
        val keyboardController = LocalSoftwareKeyboardController.current
        LaunchedEffect(listState.isScrollInProgress) {
            if (listState.isScrollInProgress) keyboardController?.hide()
        }
        val meaningSlow = rememberWaitingLong(meaning?.state?.isLoading == true)
        val crisis = meaning?.state?.crisis == true
        // The help card arrives with the server's answer, after the word results. The list keeps
        // the item it shows at the top, so without this the card would land above the screen.
        // Once per query, so Back from a verse still returns to where the user was (#324).
        var crisisShown by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(crisis) {
            if (crisis && !crisisShown) {
                crisisShown = true
                listState.scrollToItem(0)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
        ) {
            if (meaning != null && crisis) {
                // Someone may be in danger: a kind word and a way to real help before any verse,
                // then the crisis guide's verses of comfort (#324).
                item(key = "crisis_card") {
                    AiCrisisCard(
                        onFindHelpline = meaning.onFindHelpline,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
                    )
                }
                keyPassagesSection(meaning, bookNames, onVerseClicked)
            }
            if (meaning != null) {
                item(key = "words_header") { SectionHeader(stringResource(R.string.search_section_words)) }
                if (searchedVerses.isEmpty()) {
                    when {
                        meaning.wordsSearching -> Unit
                        meaning.wordsFailed -> item(key = "words_error") {
                            SectionNote(stringResource(R.string.search_error), onRetry = meaning.onSearch)
                        }
                        else -> item(key = "words_none") { SectionNote(stringResource(R.string.search_words_none)) }
                    }
                }
            }
            // GROUP BY book/chapter/verse makes this unique, unlike VerseUI.id's string hash.
            items(searchedVerses, key = { "${it.bookId}_${it.chapter}_${it.verse}" }) { verse ->
                val bookName = bookNames[verse.bookId ?: 0] ?: BibleBooks.getBookById(verse.bookId ?: 0)?.name ?: "Unknown"
                SearchResultRow(
                    reference = "$bookName ${verse.chapter}:${verse.verse}",
                    text = verse.text,
                    onClick = { onVerseClicked(verse.bookId!!, verse.chapter!!, verse.verse!!) }
                )
            }
            if (meaning != null) meaningSection(meaning, meaningSlow, bookNames, onVerseClicked)
        }
    }
}

private fun LazyListScope.meaningSection(
    meaning: MeaningSection,
    slow: Boolean,
    bookNames: Map<Int, String>,
    onVerseClicked: (bookId: Int, chapter: Int, verse: Int) -> Unit
) {
    val state = meaning.state
    // A matching topic guide's passages (#320): the ones that answer the query, above the verses
    // that are only close to it. They arrive with the results, so they never move the loading row.
    // A crisis guide's are already at the top, under the help card (#324).
    if (!state.crisis) keyPassagesSection(meaning, bookNames, onVerseClicked)
    item(key = "meaning_header") { SectionHeader(stringResource(R.string.search_section_meaning)) }
    when (state.status) {
        MeaningSearchStatus.IDLE -> item(key = "meaning_start") {
            TextButton(onClick = meaning.onSearch, modifier = Modifier.padding(horizontal = Spacing.sm)) {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.search_meaning_start), modifier = Modifier.padding(start = Spacing.sm))
            }
        }
        MeaningSearchStatus.WAKING, MeaningSearchStatus.SEARCHING -> item(key = "meaning_loading") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.search_meaning_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Spacing.md)
                    )
                }
                // The first search after Render's server slept waits up to a minute (#280).
                if (slow) ServerWakeHint(modifier = Modifier.padding(top = Spacing.sm))
            }
        }
        MeaningSearchStatus.OFFLINE -> item(key = "meaning_offline") {
            SectionNote(stringResource(R.string.search_meaning_offline), onRetry = meaning.onSearch)
        }
        MeaningSearchStatus.UNAVAILABLE -> item(key = "meaning_unavailable") {
            SectionNote(stringResource(R.string.search_meaning_unavailable), onRetry = meaning.onSearch)
        }
        MeaningSearchStatus.DONE -> {
            if (state.results.isEmpty()) {
                item(key = "meaning_none") { SectionNote(stringResource(R.string.search_meaning_none)) }
            }
            items(state.results, key = { "meaning_${it.bookId}_${it.chapter}_${it.verse}" }) { verse ->
                val bookName = bookNames[verse.bookId] ?: BibleBooks.getBookById(verse.bookId)?.name ?: "Unknown"
                // From the bundled KJV when the selected translation lacks the verse: say so.
                val label = if (verse.versionId == meaning.selectedVersionId) "" else " · ${meaning.versionLabel(verse.versionId)}"
                SearchResultRow(
                    reference = "$bookName ${verse.chapter}:${verse.verse}$label",
                    text = verse.text,
                    onClick = { onVerseClicked(verse.bookId, verse.chapter, verse.verse) }
                )
            }
        }
    }
}

/** The "Key passages" header and rows (#320), when the search is done and a guide matched. */
private fun LazyListScope.keyPassagesSection(
    meaning: MeaningSection,
    bookNames: Map<Int, String>,
    onVerseClicked: (bookId: Int, chapter: Int, verse: Int) -> Unit
) {
    val state = meaning.state
    if (state.status != MeaningSearchStatus.DONE || state.keyPassages.isEmpty()) return
    item(key = "key_header") { SectionHeader(stringResource(R.string.search_section_key_passages)) }
    items(state.keyPassages, key = { "key_${it.reference}" }) { passage ->
        KeyPassageRow(passage, meaning, bookNames, onVerseClicked)
    }
}

/** One key passage (#320): a verse, or a range or whole chapter with its verses numbered, cut short when long. */
@Composable
private fun KeyPassageRow(
    passage: KeyPassage,
    meaning: MeaningSection,
    bookNames: Map<Int, String>,
    onVerseClicked: (bookId: Int, chapter: Int, verse: Int) -> Unit
) {
    val reference = formatVerseReference(passage.reference) { bookNames[it] ?: BibleBooks.getBookById(it)?.name ?: "Unknown" }
    // From the bundled KJV when the selected translation lacks part of it: say so.
    val label = if (passage.versionId == meaning.selectedVersionId) "" else " · ${meaning.versionLabel(passage.versionId)}"
    SearchResultRow(
        reference = reference + label,
        text = passageText(passage.verses, MaterialTheme.colorScheme.onSurfaceVariant),
        // A whole chapter (Psalm 73) or a long range (Ephesians 5:21-33) would push everything else
        // off the screen; the reader is a tap away. 8 lines, like the Bible Helper's verse cards.
        maxLines = KEY_PASSAGE_MAX_LINES,
        onClick = { onVerseClicked(passage.reference.bookId, passage.reference.chapter, passage.reference.startVerse ?: 1) }
    )
}

private const val KEY_PASSAGE_MAX_LINES = 8

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() }
            .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.lg, bottom = Spacing.xs)
    )
}

/** One quiet line in place of a section's results, with Retry when trying again may help. */
@Composable
private fun SectionNote(text: String, onRetry: (() -> Unit)? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

@Composable
private fun SearchResultRow(reference: String, text: String, onClick: () -> Unit) =
    SearchResultRow(reference, AnnotatedString(text), onClick = onClick)

@Composable
private fun SearchResultRow(reference: String, text: AnnotatedString, maxLines: Int = Int.MAX_VALUE, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
    ) {
        Text(
            text = reference,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.xs)
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
