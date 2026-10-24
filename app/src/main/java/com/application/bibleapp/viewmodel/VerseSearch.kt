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

/**
 * A handful of common topics to search for before the user has typed anything themselves, keyed
 * by the catalog's ISO 639-3 language code. They're matched against the Bible's own text, so they
 * follow the version's language, not the app's (#88). Search matches parts of words, so very
 * short words that hide inside others ("fe" in "profeta", "foi" in "fois") are left out.
 * Not every word finds something in every translation: [SearchSuggestionFilter] hides those.
 * Korean and Chinese are missing because search can't match their scripts yet.
 */
private val SEARCH_SUGGESTIONS: Map<String, List<String>> = mapOf(
    "eng" to listOf("love", "faith", "hope", "grace", "peace", "joy", "forgiveness"),
    "spa" to listOf("amor", "esperanza", "gracia", "paz", "gozo", "perdón"),
    "por" to listOf("amor", "esperança", "graça", "paz", "alegria", "perdão"),
    "fra" to listOf("amour", "espérance", "grâce", "paix", "joie", "pardon"),
    "deu" to listOf("Liebe", "Glaube", "Hoffnung", "Gnade", "Friede", "Freude", "Vergebung"),
    "ind" to listOf("kasih", "iman", "pengharapan", "kasih karunia", "damai", "sukacita", "pengampunan"),
    "tgl" to listOf("pag-ibig", "pananampalataya", "biyaya", "kapayapaan", "kagalakan", "kapatawaran"),
    "swh" to listOf("upendo", "imani", "tumaini", "neema", "amani", "furaha", "msamaha")
)

/**
 * The empty Search screen's candidate suggestions for [versionId], in its language. None while
 * [catalog] hasn't loaded, since the language is unknown then, except for the bundled KJV.
 */
fun searchSuggestionsFor(versionId: String, catalog: List<BibleTranslation>): List<String> {
    val languageCode = if (versionId == DEFAULT_VERSION.id) ENGLISH_LANGUAGE_CODE
    else catalog.firstOrNull { it.id == versionId }?.languageCode
    return SEARCH_SUGGESTIONS[languageCode].orEmpty()
}

/** The catalog's ISO 639-3 code for English. */
private const val ENGLISH_LANGUAGE_CODE = "eng"

/**
 * Keeps only the suggestions that find something in the version, so no chip ever leads to
 * "No results" (#60, #88): translations word things differently ("gozo" or "alegría"), and
 * search can't match every accent yet. Answers are remembered per version and word, since a
 * version's text doesn't change while it's installed. A failed check isn't remembered.
 */
class SearchSuggestionFilter(
    private val hasMatch: suspend (word: String, versionId: String) -> Boolean
) {
    private val known = mutableMapOf<Pair<String, String>, Boolean>()

    /** [candidates] filtered without touching the database, or null if one hasn't been checked. */
    @Synchronized
    fun cached(versionId: String, candidates: List<String>): List<String>? {
        val answers = candidates.map { known[versionId to it] ?: return null }
        return candidates.filterIndexed { i, _ -> answers[i] }
    }

    suspend fun matching(versionId: String, candidates: List<String>): List<String> =
        candidates.filter { word ->
            synchronized(this) { known[versionId to word] } ?: try {
                hasMatch(word, versionId).also { synchronized(this) { known[versionId to word] = it } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
}

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
