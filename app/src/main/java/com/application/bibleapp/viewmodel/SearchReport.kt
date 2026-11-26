package com.application.bibleapp.viewmodel

import com.application.bibleapp.data.model.UsfmBookCodes
import com.application.bibleapp.data.remote.SearchReportRequestDto
import com.application.bibleapp.data.remote.SemanticSearchApi
import com.application.bibleapp.utils.VerseReference
import com.application.bibleapp.utils.formatVerseReference

/**
 * What reporting a search (#402) will send, all of it shown in the report sheet before Send:
 * the search, the translation the results were shown in ([bibleVersionId], never its text), and
 * the references "Related by meaning" ([results]) and "Key passages" ([keyPassages]) showed, in
 * their order. The app's language and version are added when it's sent ([toRequest]).
 */
data class SearchReportDraft(
    val query: String,
    val bibleVersionId: String,
    val results: List<VerseReference>,
    val keyPassages: List<VerseReference>
)

/** The contract's limit on each list of references. */
private const val MAX_REPORT_REFERENCES = 20

/**
 * The report for this search by meaning, or null until its results are in: the link appears only
 * then, so a search that never reached the server (offline) can't be reported. [results] are the
 * server's verses before the word results are taken out, so the report has the whole answer.
 */
fun MeaningSearchState.reportDraft(bibleVersionId: String): SearchReportDraft? {
    if (status != MeaningSearchStatus.DONE || query.isBlank()) return null
    return SearchReportDraft(
        query = query.take(SemanticSearchApi.MAX_QUERY_CHARS),
        bibleVersionId = bibleVersionId,
        results = results.map { VerseReference(it.bookId, it.chapter, it.verse) }.take(MAX_REPORT_REFERENCES),
        keyPassages = keyPassages.map { it.reference }.take(MAX_REPORT_REFERENCES)
    )
}

/** The request for [SemanticSearchApi.report]; [language] is an AppLanguage tag. */
fun SearchReportDraft.toRequest(language: String, appVersion: String) = SearchReportRequestDto(
    q = query,
    language = language,
    bibleVersion = bibleVersionId,
    appVersion = appVersion.take(MAX_APP_VERSION_CHARS),
    results = results.mapNotNull(::usfmReference),
    keyPassages = keyPassages.mapNotNull(::usfmReference)
)

private const val MAX_APP_VERSION_CHARS = 20

/** "JHN 3:16", "MAT 11:28-30" or "PSA 73", as the server's key passages are written (#320). */
internal fun usfmReference(reference: VerseReference): String? =
    UsfmBookCodes.code(reference.bookId)?.let { code -> formatVerseReference(reference) { code } }
