package com.application.bibleapp.viewmodel

import com.application.bibleapp.data.model.DEFAULT_VERSION
import com.application.bibleapp.data.model.PassageVerse
import com.application.bibleapp.data.model.VerseUI
import com.application.bibleapp.data.model.LicensedBibles
import com.application.bibleapp.data.model.loadAiVersePassage
import com.application.bibleapp.data.model.passageIn
import com.application.bibleapp.data.remote.SemanticSearchResult
import com.application.bibleapp.data.remote.ServerWake
import com.application.bibleapp.data.remote.VerseRef
import com.application.bibleapp.utils.VerseReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

/** One of a matching topic guide's passages (#320), in [versionId] like [MeaningVerse]. */
data class KeyPassage(val reference: VerseReference, val verses: List<PassageVerse>, val versionId: String)

/**
 * The "Related by meaning" section, and the "Key passages" above it when the query matched a topic
 * guide (#320). [query] is the submitted query [results] and [keyPassages] belong to; [results]
 * leaves out the verses [keyPassages] show. [crisis] (#324): the query matched the crisis guide, so
 * the Bible Helper's help card comes first; kept even when the verses couldn't be read. When the
 * server can't answer (offline, unavailable), the phone's own crisis phrases decide instead (#330).
 * [suggestAsk] (#401): the server says the query reads like a request for help, so Search offers to
 * ask the Bible Helper; only from the server, so never offline (the Bible Helper needs it anyway).
 */
data class MeaningSearchState(
    val query: String = "",
    val status: MeaningSearchStatus = MeaningSearchStatus.IDLE,
    val results: List<MeaningVerse> = emptyList(),
    val keyPassages: List<KeyPassage> = emptyList(),
    val crisis: Boolean = false,
    val suggestAsk: Boolean = false
) {
    val isLoading: Boolean get() = status == MeaningSearchStatus.WAKING || status == MeaningSearchStatus.SEARCHING
}

/**
 * Search by meaning for the Search screen (#287), next to the word search ([VerseSearch]). It
 * runs only when a search is submitted, never per keystroke: one server request (and a few of
 * Cloudflare's free neurons) per real search. Typing something else clears it, and a new
 * submission cancels the one before. The verses come back as references only; [resolve] and
 * [resolvePassages] (the key passages, #320) read them from the Bibles on the phone, again after a
 * version change. [isCrisis] is the phone's fallback for the help card when the server can't
 * answer (#330): offline or unavailable only, as the server's crisis guide knows far more ways to
 * say it.
 */
class MeaningSearch(
    private val scope: CoroutineScope,
    private val search: suspend (query: String) -> SemanticSearchResult,
    private val resolve: suspend (List<VerseRef>) -> List<MeaningVerse>,
    private val isEligible: (query: String) -> Boolean,
    private val phoneIsOnline: () -> Boolean = { true },
    private val serverWake: ServerWake? = null,
    private val resolvePassages: suspend (List<VerseReference>) -> List<KeyPassage> = { emptyList() },
    private val isCrisis: (query: String) -> Boolean = { false }
) {
    private val _state = MutableStateFlow(MeaningSearchState())
    val state: StateFlow<MeaningSearchState> = _state.asStateFlow()

    private var job: Job? = null
    private var found: SemanticSearchResult.Found? = null

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
        val found = found ?: return
        if (current.status != MeaningSearchStatus.DONE || (found.verses.isEmpty() && found.keyPassages.isEmpty())) return
        val query = current.query
        job?.cancel()
        job = scope.launch {
            // copy() keeps the help card (#324).
            val done = doneOrNull(query, found) ?: return@launch _state.update { it.copy(status = MeaningSearchStatus.UNAVAILABLE) }
            _state.value = done
        }
    }

    private fun reset() {
        job?.cancel()
        found = null
        _state.value = MeaningSearchState()
    }

    private fun launch(query: String) {
        job?.cancel()
        found = null
        _state.value = MeaningSearchState(query, MeaningSearchStatus.SEARCHING)
        job = scope.launch {
            if (!phoneIsOnline()) {
                _state.value = unanswered(query, MeaningSearchStatus.OFFLINE)
                return@launch
            }
            serverWake?.takeUnless { it.isAwake() }?.let { wake ->
                _state.value = MeaningSearchState(query, MeaningSearchStatus.WAKING)
                wake.wake()
                _state.value = MeaningSearchState(query, MeaningSearchStatus.SEARCHING)
            }
            _state.value = when (val result = search(query)) {
                is SemanticSearchResult.Found -> {
                    found = result
                    // The help card never depends on reading the verses (#324).
                    doneOrNull(query, result)
                        ?: MeaningSearchState(query, MeaningSearchStatus.UNAVAILABLE, crisis = result.crisis, suggestAsk = result.suggestAsk)
                }
                SemanticSearchResult.Offline -> unanswered(query, MeaningSearchStatus.OFFLINE)
                SemanticSearchResult.Unavailable -> unanswered(query, MeaningSearchStatus.UNAVAILABLE)
            }
        }
    }

    /** The server couldn't answer: the help card comes from the phone's own phrases (#330). Not
     *  when it did answer and the verses failed to load, where its own `crisis` stands. */
    private fun unanswered(query: String, status: MeaningSearchStatus) =
        MeaningSearchState(query, status, crisis = isCrisis(query))

    /** [found] read from the phone's Bibles, key passages first, or null when that failed. */
    private suspend fun doneOrNull(query: String, found: SemanticSearchResult.Found): MeaningSearchState? = try {
        val passages = if (found.keyPassages.isEmpty()) emptyList() else resolvePassages(found.keyPassages)
        val verses = resolve(found.verses).withoutKeyPassages(passages)
        MeaningSearchState(query, MeaningSearchStatus.DONE, verses, passages, found.crisis, found.suggestAsk)
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

/** [verses] without the ones [passages] already show (#320): each verse appears once in the section. */
fun List<MeaningVerse>.withoutKeyPassages(passages: List<KeyPassage>): List<MeaningVerse> {
    if (passages.isEmpty()) return this
    return filterNot { verse ->
        passages.any { it.reference.bookId == verse.bookId && it.reference.chapter == verse.chapter && it.reference.includes(verse.verse) }
    }
}

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
 *
 * A licensed version (#383) shows the whole list or none of it: its chapters not saved yet are
 * fetched [LICENSED_PARALLEL_CHAPTERS] at a time, as its word search does (#382), and if one can't
 * be (offline, a 429) or lacks a verse, the whole list is the KJV's, never a mix. A version whose
 * terms bar any AI use of its text (the ESV, #377) is never shown: the list is the KJV's.
 */
suspend fun resolveMeaningVerses(
    refs: List<VerseRef>,
    selectedVersionId: String,
    chapterOf: suspend (versionId: String, bookId: Int, chapter: Int) -> List<VerseUI>
): List<MeaningVerse> {
    if (!LicensedBibles.allowsAi(selectedVersionId)) return resolveMeaningVerses(refs, DEFAULT_VERSION.id, chapterOf)
    if (LicensedBibles.isLicensed(selectedVersionId)) {
        return licensedMeaningVerses(refs, selectedVersionId, chapterOf)
            ?: resolveMeaningVerses(refs, DEFAULT_VERSION.id, chapterOf)
    }
    val chapters = mutableMapOf<Triple<String, Int, Int>, List<VerseUI>>()
    return refs.mapNotNull { ref ->
        val reference = VerseReference(ref.bookId, ref.chapter, ref.verse, ref.verse)
        val passage = loadAiVersePassage(reference, selectedVersionId) { versionId ->
            chapters.getOrPut(Triple(versionId, ref.bookId, ref.chapter)) { chapterOf(versionId, ref.bookId, ref.chapter) }
        }
        passage?.verses?.firstOrNull()?.let { MeaningVerse(ref.bookId, ref.chapter, ref.verse, it.text, passage.versionId) }
    }
}

/** All of [refs] in the licensed [versionId], or null as soon as one chapter can't be loaded or one verse is missing. */
private suspend fun licensedMeaningVerses(
    refs: List<VerseRef>,
    versionId: String,
    chapterOf: suspend (versionId: String, bookId: Int, chapter: Int) -> List<VerseUI>
): List<MeaningVerse>? {
    val permits = Semaphore(LICENSED_PARALLEL_CHAPTERS)
    val failed = AtomicBoolean(false) // once one fails the list is the KJV's: the chapters still waiting aren't fetched
    val chapters = coroutineScope {
        refs.map { it.bookId to it.chapter }.distinct().map { (bookId, chapter) ->
            async {
                permits.withPermit {
                    if (failed.get()) return@withPermit null
                    chapterOf(versionId, bookId, chapter).takeIf { it.isNotEmpty() }.also { if (it == null) failed.set(true) }
                }?.let { (bookId to chapter) to it }
            }
        }.awaitAll()
    }
    if (failed.get()) return null
    val byChapter = chapters.filterNotNull().toMap()
    return refs.map { ref ->
        val verse = passageIn(byChapter[ref.bookId to ref.chapter].orEmpty(), VerseReference(ref.bookId, ref.chapter, ref.verse, ref.verse))
            .firstOrNull() ?: return null
        MeaningVerse(ref.bookId, ref.chapter, ref.verse, verse.text, versionId)
    }
}

/** How many of a licensed version's chapters search by meaning fetches at once (#383), as its word search does. */
const val LICENSED_PARALLEL_CHAPTERS = 3

/**
 * Reads search's key passages (#320) from the phone's Bibles, each whole from one translation as
 * the Bible Helper's verse cards are ([loadAiVersePassage]): [selectedVersionId], or the bundled
 * KJV when it lacks part of a passage. A passage neither has is left out.
 */
suspend fun resolveKeyPassages(
    refs: List<VerseReference>,
    selectedVersionId: String,
    chapterOf: suspend (versionId: String, bookId: Int, chapter: Int) -> List<VerseUI>
): List<KeyPassage> {
    val chapters = mutableMapOf<Triple<String, Int, Int>, List<VerseUI>>()
    return refs.mapNotNull { ref ->
        loadAiVersePassage(ref, selectedVersionId) { versionId ->
            chapters.getOrPut(Triple(versionId, ref.bookId, ref.chapter)) { chapterOf(versionId, ref.bookId, ref.chapter) }
        }?.let { KeyPassage(ref, it.verses, it.versionId) }
    }
}
