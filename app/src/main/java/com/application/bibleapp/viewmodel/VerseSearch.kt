package com.application.bibleapp.viewmodel

import com.application.bibleapp.data.model.BibleTranslation
import com.application.bibleapp.data.model.DEFAULT_VERSION
import com.application.bibleapp.data.model.VerseUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the Search screen shows. [query] is the query [results] belong to, not the live text
 * field, so typing never makes the screen claim "no results" for a search that hasn't run yet.
 * While [isSearching], the previous [results] stay on screen under a progress bar.
 */
data class SearchUiState(
    val query: String = "",
    val results: List<VerseUI> = emptyList(),
    val isSearching: Boolean = false,
    val hasError: Boolean = false
)

/** A handful of common topics to search for before the user has typed anything themselves. */
private val ENGLISH_SEARCH_SUGGESTIONS = listOf("love", "faith", "hope", "grace", "peace", "joy", "forgiveness")

/**
 * The empty Search screen's suggestions for [versionId]. They're English words matched against the
 * Bible's own text, so they're only offered for an English version: in any other language every
 * one found nothing (#60). Also none while [catalog] hasn't loaded, since the language is unknown.
 */
fun searchSuggestionsFor(versionId: String, catalog: List<BibleTranslation>): List<String> {
    val isEnglish = versionId == DEFAULT_VERSION.id ||
        catalog.firstOrNull { it.id == versionId }?.languageCode == ENGLISH_LANGUAGE_CODE
    return if (isEnglish) ENGLISH_SEARCH_SUGGESTIONS else emptyList()
}

/** The catalog's ISO 639-3 code for English. */
private const val ENGLISH_LANGUAGE_CODE = "eng"

/**
 * Debounced search-as-you-type for the Search screen: a search runs once typing pauses for
 * [debounceMillis], or right away on [submit]. Every new request cancels the one before it,
 * so a slow search for an earlier query can never overwrite the results for a newer one.
 * The version is read when the search actually runs, see [onVersionChange].
 */
class VerseSearch(
    private val scope: CoroutineScope,
    private val search: suspend (query: String, versionId: String) -> List<VerseUI>,
    private val currentVersionId: () -> String,
    private val debounceMillis: Long = DEBOUNCE_MILLIS
) {
    private val _query = MutableStateFlow("")
    /** The search field's text, exactly as typed. */
    val query: StateFlow<String> = _query.asStateFlow()

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var requestedQuery = ""

    /** The text changed: search it once typing pauses, or go back to idle if it's too short to search. */
    fun onQueryChange(text: String) {
        _query.value = text
        val query = text.trim()
        if (query.length < MIN_QUERY_LENGTH) {
            reset()
            return
        }
        // "love" -> "love " shouldn't search again, and neither should re-typing a pending query.
        val inFlight = job?.isActive == true
        if (inFlight && requestedQuery == query) return
        if (!inFlight && _state.value.query == query && !_state.value.hasError) return
        launchSearch(query, debounceMillis)
    }

    /** The keyboard's Search key, a suggestion chip or Retry: search now instead of waiting out the debounce. */
    fun submit() {
        val query = _query.value.trim()
        if (query.isNotEmpty()) launchSearch(query, delayMillis = 0)
    }

    /** The Bible version changed. Results on screen (or a search already running) belong to the old one, so search again. */
    fun onVersionChange() {
        val query = if (job?.isActive == true) requestedQuery else _state.value.query
        if (query.isNotEmpty()) launchSearch(query, delayMillis = 0)
    }

    private fun reset() {
        job?.cancel()
        requestedQuery = ""
        _state.value = SearchUiState()
    }

    private fun launchSearch(query: String, delayMillis: Long) {
        job?.cancel()
        requestedQuery = query
        job = scope.launch {
            delay(delayMillis)
            _state.update { it.copy(isSearching = true) }
            _state.value = try {
                SearchUiState(query = query, results = search(query, currentVersionId()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SearchUiState(query = query, hasError = true)
            }
        }
    }

    companion object {
        const val DEBOUNCE_MILLIS = 300L
        /** Shorter queries match nearly every verse, so they only run when submitted explicitly. */
        const val MIN_QUERY_LENGTH = 2
    }
}
