package com.application.bibleapp.viewmodel

import com.application.bibleapp.data.model.VerseUI
import com.application.bibleapp.data.model.loadAiVersePassage
import com.application.bibleapp.data.remote.SemanticSearchResult
import com.application.bibleapp.data.remote.ServerWake
import com.application.bibleapp.data.remote.VerseRef
import com.application.bibleapp.utils.VerseReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the Search screen's "Related by meaning" section is (#287). */
enum class MeaningSearchStatus {
    /** Nothing submitted for this query yet: the section offers to search. */
    IDLE,
    /** Waiting for our server to wake up first (#280); the loading row says so if it's slow. */
    WAKING,
    SEARCHING,
    DONE,
    OFFLINE,
    /** The server said no (out of neurons, too many searches) or failed. */
    UNAVAILABLE
}

/** One verse search by meaning found, in [versionId]: the selected translation, or the bundled
 *  KJV when the selected one doesn't have it. */
data class MeaningVerse(val bookId: Int, val chapter: Int, val verse: Int, val text: String, val versionId: String)

/** The "Related by meaning" section. [query] is the submitted query [results] belong to. */
data class MeaningSearchState(
    val query: String = "",
    val status: MeaningSearchStatus = MeaningSearchStatus.IDLE,
    val results: List<MeaningVerse> = emptyList()
) {
    val isLoading: Boolean get() = status == MeaningSearchStatus.WAKING || status == MeaningSearchStatus.SEARCHING
}

/**
 * Search by meaning for the Search screen (#287), next to the word search ([VerseSearch]). It
 * runs only when a search is submitted, never per keystroke: one server request (and a few of
 * Cloudflare's free neurons) per real search. Typing something else clears it, and a new
 * submission cancels the one before. The verses come back as references only; [resolve] reads
 * them from the Bibles on the phone, again after a version change.
 */
class MeaningSearch(
    private val scope: CoroutineScope,
    private val search: suspend (query: String) -> SemanticSearchResult,
    private val resolve: suspend (List<VerseRef>) -> List<MeaningVerse>,
    private val isEligible: (query: String) -> Boolean,
    private val phoneIsOnline: () -> Boolean = { true },
    private val serverWake: ServerWake? = null
) {
    private val _state = MutableStateFlow(MeaningSearchState())
    val state: StateFlow<MeaningSearchState> = _state.asStateFlow()

    private var job: Job? = null
    private var refs: List<VerseRef> = emptyList()

    /** The search field changed: what's shown belongs to another query now ("love" → "love " doesn't count). */
    fun onQueryChange(text: String) {
        if (text.trim() != _state.value.query) reset()
    }

    /** Enter, the search button, a suggestion chip, or the section's own button. The same query
     *  isn't sent twice unless the last try failed. */
    fun submit(text: String) {
        val query = text.trim()
        if (!isEligible(query)) return reset()
        val current = _state.value
        if (current.query == query && (current.isLoading || current.status == MeaningSearchStatus.DONE)) return
        launch(query)
    }

    /** The reader's translation changed: show the same verses in the new one, without asking the server again. */
    fun onVersionChange() {
        val current = _state.value
        if (current.status != MeaningSearchStatus.DONE || refs.isEmpty()) return
        val query = current.query
        job?.cancel()
        job = scope.launch {
            val verses = resolveOrNull(refs) ?: return@launch _state.update { it.copy(status = MeaningSearchStatus.UNAVAILABLE) }
            _state.value = MeaningSearchState(query, MeaningSearchStatus.DONE, verses)
        }
    }

    private fun reset() {
        job?.cancel()
        refs = emptyList()
        _state.value = MeaningSearchState()
    }

    private fun launch(query: String) {
        job?.cancel()
        refs = emptyList()
        _state.value = MeaningSearchState(query, MeaningSearchStatus.SEARCHING)
        job = scope.launch {
            if (!phoneIsOnline()) {
                _state.value = MeaningSearchState(query, MeaningSearchStatus.OFFLINE)
                return@launch
            }
            serverWake?.takeUnless { it.isAwake() }?.let { wake ->
                _state.value = MeaningSearchState(query, MeaningSearchStatus.WAKING)
                wake.wake()
                _state.value = MeaningSearchState(query, MeaningSearchStatus.SEARCHING)
            }
            _state.value = when (val result = search(query)) {
                is SemanticSearchResult.Found -> {
                    refs = result.verses
                    resolveOrNull(result.verses)?.let { MeaningSearchState(query, MeaningSearchStatus.DONE, it) }
                        ?: MeaningSearchState(query, MeaningSearchStatus.UNAVAILABLE)
                }
                SemanticSearchResult.Offline -> MeaningSearchState(query, MeaningSearchStatus.OFFLINE)
                SemanticSearchResult.Unavailable -> MeaningSearchState(query, MeaningSearchStatus.UNAVAILABLE)
            }
        }
    }

    private suspend fun resolveOrNull(verses: List<VerseRef>): List<MeaningVerse>? = try {
        resolve(verses)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/**
 * Whether a submitted [query] gets a search by meaning too (#287). Not for a verse reference
 * ("John 3:16", [isReference]) or a quoted phrase, where the word search already finds what's
 * wanted, nor for a query too short to mean anything (one letter; one Chinese character is a
 * word, #90).
 */
fun isMeaningSearchEligible(query: String, isReference: (String) -> Boolean): Boolean {
    val text = query.trim()
    if (text.length < VerseSearch.MIN_QUERY_LENGTH && !text.isIdeograph()) return false
    if (text.first() in OPENING_QUOTES) return false
    return !isReference(text)
}

/** Quotation marks a phrase search may start with, in the app's languages. */
private const val OPENING_QUOTES = "\"“”„«»‹›「『"

/** [verses] without the ones [wordResults] already show: each verse appears once on the screen. */
fun List<MeaningVerse>.withoutWordResults(wordResults: List<VerseUI>): List<MeaningVerse> {
    if (wordResults.isEmpty()) return this
    val shown = wordResults.mapTo(HashSet()) { Triple(it.bookId, it.chapter, it.verse) }
    return filterNot { Triple(it.bookId, it.chapter, it.verse) in shown }
}

/**
 * Reads [refs] from the phone's Bibles: [selectedVersionId], or the bundled KJV when it doesn't
 * have a verse, as the Bible Helper's verse cards do ([loadAiVersePassage], #129). A verse
 * neither has is left out. Each chapter is read once per call, however many verses it has.
 */
suspend fun resolveMeaningVerses(
    refs: List<VerseRef>,
    selectedVersionId: String,
    chapterOf: suspend (versionId: String, bookId: Int, chapter: Int) -> List<VerseUI>
): List<MeaningVerse> {
    val chapters = mutableMapOf<Triple<String, Int, Int>, List<VerseUI>>()
    return refs.mapNotNull { ref ->
        val reference = VerseReference(ref.bookId, ref.chapter, ref.verse, ref.verse)
        val passage = loadAiVersePassage(reference, selectedVersionId) { versionId ->
            chapters.getOrPut(Triple(versionId, ref.bookId, ref.chapter)) { chapterOf(versionId, ref.bookId, ref.chapter) }
        }
        passage?.verses?.firstOrNull()?.let { MeaningVerse(ref.bookId, ref.chapter, ref.verse, it.text, passage.versionId) }
    }
}
