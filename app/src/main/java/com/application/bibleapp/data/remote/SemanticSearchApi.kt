package com.application.bibleapp.data.remote

import android.util.Log
import com.application.bibleapp.data.model.UsfmBookCodes
import com.application.bibleapp.utils.VerseReference
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
data class SemanticSearchRequestDto(val q: String)

@Serializable
data class SemanticSearchResultDto(val bookId: Int, val chapter: Int, val verse: Int, val score: Double)

/** [keyPassages] (#320): a matching topic guide's passages as USFM references; absent when none matched.
 *  [crisis] (#324): true when that guide is the crisis guide; absent otherwise.
 *  [suggestAsk] (#401): true when the search reads like a request for help; absent otherwise. */
@Serializable
data class SemanticSearchResponseDto(
    val results: List<SemanticSearchResultDto>,
    val keyPassages: List<String>? = null,
    val crisis: Boolean? = null,
    val suggestAsk: Boolean? = null
)

/**
 * `POST /api/v1/search/reports` (#402): a search whose results didn't help, sent only from the
 * sheet that shows all of it, on Send. References are USFM ("JHN 3:16", "PSA 73"); [bibleVersion]
 * is the translation's ID, never its text.
 */
@Serializable
data class SearchReportRequestDto(
    val q: String,
    val language: String,
    val bibleVersion: String,
    val appVersion: String? = null,
    val results: List<String> = emptyList(),
    val keyPassages: List<String> = emptyList()
)

/** How reporting a search (#402) ended, one snackbar line per case. */
enum class SearchReportResult { SENT, OFFLINE, RATE_LIMITED, FAILED }

/** One verse search by meaning found; [bookId] is 1–66, as everywhere in the app. */
data class VerseRef(val bookId: Int, val chapter: Int, val verse: Int)

/** Every way a search by meaning can end (#287), so the Search screen has one line per case. */
sealed interface SemanticSearchResult {
    /** Best first; empty when nothing matched (or the server's verses aren't loaded yet).
     *  [keyPassages] (#320): the passages of the topic guide the query matched, to show first.
     *  [crisis] (#324): the query matched the crisis guide, so the help card comes before anything.
     *  [suggestAsk] (#401): it reads like a request for help, so Search offers the Bible Helper. */
    data class Found(
        val verses: List<VerseRef>,
        val keyPassages: List<VerseReference> = emptyList(),
        val crisis: Boolean = false,
        val suggestAsk: Boolean = false
    ) : SemanticSearchResult

    /** The phone couldn't reach the server. */
    data object Offline : SemanticSearchResult

    /** The server said no (503: the AI is out of free neurons or failing; 429: too many searches
     *  a minute), didn't answer in time, or answered something unreadable. */
    data object Unavailable : SemanticSearchResult
}

/**
 * `POST /api/v1/search/semantic` (docs/api_contract.md, "Search by meaning", #287). The request
 * carries no account: it's [anonymousBackendRequest], so the search text is never linked to who
 * typed it. [client] is swappable for tests (MockEngine). [phoneIsOnline], when given, tells a
 * phone that's really offline from a connection that dropped while the server was slow (#280).
 */
class SemanticSearchApi(
    private val client: () -> HttpClient = { HttpClientProvider.client },
    private val baseUrl: String = HttpClientProvider.BASE_URL,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
    private val phoneIsOnline: (() -> Boolean)? = null
) {

    /** [query] is trimmed and cut to the contract's [MAX_QUERY_CHARS]; it must not be blank. */
    suspend fun search(query: String): SemanticSearchResult = try {
        val q = query.trim().take(MAX_QUERY_CHARS)
        require(q.isNotEmpty()) { "Blank search" }
        val response = client().post("$baseUrl/search/semantic") {
            anonymousBackendRequest()
            contentType(ContentType.Application.Json)
            setBody(SemanticSearchRequestDto(q))
        }
        if (response.status.isSuccess()) {
            val body = response.body<SemanticSearchResponseDto>()
            SemanticSearchResult.Found(
                verses = body.results.map { VerseRef(it.bookId, it.chapter, it.verse) },
                // One the app can't read (a newer server's format) is left out, never an error.
                keyPassages = body.keyPassages.orEmpty().mapNotNull(UsfmBookCodes::parseReference).distinct(),
                crisis = body.crisis == true,
                suggestAsk = body.suggestAsk == true
            )
        } else {
            // 503 and 429 are expected on a busy day; a 400 would be our own bug (the query is
            // trimmed and cut to the contract's size above). The person can't fix either.
            log("Semantic search rejected: ${response.status.value}")
            SemanticSearchResult.Unavailable
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("Semantic search failed: ${e.javaClass.simpleName}: ${e.message}")
        when {
            isServerResponseTimeout(e) -> SemanticSearchResult.Unavailable
            // A connection that dropped while the phone is online isn't "you're offline" (#280).
            isRetriableNetworkError(e) ->
                if (phoneIsOnline?.invoke() == true) SemanticSearchResult.Unavailable else SemanticSearchResult.Offline
            else -> SemanticSearchResult.Unavailable
        }
    }

    /** Sends [request] without the account, like the search itself ([anonymousBackendRequest]). */
    suspend fun report(request: SearchReportRequestDto): SearchReportResult = try {
        val response = client().post("$baseUrl/search/reports") {
            anonymousBackendRequest()
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        when {
            response.status.isSuccess() -> SearchReportResult.SENT
            // The `search-report` zone: 10 an hour per IP.
            response.status == HttpStatusCode.TooManyRequests -> SearchReportResult.RATE_LIMITED
            else -> {
                // A 400 would be our own bug (the app sends only what the contract allows).
                log("Search report rejected: ${response.status.value}")
                SearchReportResult.FAILED
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("Search report failed: ${e.javaClass.simpleName}: ${e.message}")
        when {
            isServerResponseTimeout(e) -> SearchReportResult.FAILED
            isRetriableNetworkError(e) ->
                if (phoneIsOnline?.invoke() == true) SearchReportResult.FAILED else SearchReportResult.OFFLINE
            else -> SearchReportResult.FAILED
        }
    }

    companion object {
        private const val TAG = "SemanticSearchApi"

        /** The contract's limit; the server answers 400 past it. */
        const val MAX_QUERY_CHARS = 200
    }
}
