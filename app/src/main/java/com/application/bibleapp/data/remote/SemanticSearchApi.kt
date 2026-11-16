package com.application.bibleapp.data.remote

import android.util.Log
import com.application.bibleapp.data.model.UsfmBookCodes
import com.application.bibleapp.utils.VerseReference
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
data class SemanticSearchRequestDto(val q: String)

@Serializable
data class SemanticSearchResultDto(val bookId: Int, val chapter: Int, val verse: Int, val score: Double)

/** [keyPassages] (#320): a matching topic guide's passages as USFM references; absent when none matched. */
@Serializable
data class SemanticSearchResponseDto(val results: List<SemanticSearchResultDto>, val keyPassages: List<String>? = null)

/** One verse search by meaning found; [bookId] is 1–66, as everywhere in the app. */
data class VerseRef(val bookId: Int, val chapter: Int, val verse: Int)

/** Every way a search by meaning can end (#287), so the Search screen has one line per case. */
sealed interface SemanticSearchResult {
    /** Best first; empty when nothing matched (or the server's verses aren't loaded yet).
     *  [keyPassages] (#320): the passages of the topic guide the query matched, to show first. */
    data class Found(val verses: List<VerseRef>, val keyPassages: List<VerseReference> = emptyList()) : SemanticSearchResult

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
                keyPassages = body.keyPassages.orEmpty().mapNotNull(UsfmBookCodes::parseReference).distinct()
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

    companion object {
        private const val TAG = "SemanticSearchApi"

        /** The contract's limit; the server answers 400 past it. */
        const val MAX_QUERY_CHARS = 200
    }
}
