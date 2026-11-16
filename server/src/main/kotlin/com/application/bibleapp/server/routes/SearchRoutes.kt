package com.application.bibleapp.server.routes

import com.application.bibleapp.server.ai.AiProviderException
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.SemanticSearchRequest
import com.application.bibleapp.server.models.SemanticSearchResponse
import com.application.bibleapp.server.models.SemanticSearchResult
import com.application.bibleapp.server.plugins.SEARCH_RATE_LIMIT
import com.application.bibleapp.server.search.VerseSearch
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException

/** The longest query: a sentence, not a page, so a request can't spend much (#287). */
const val SEARCH_MAX_CHARS = 200

/**
 * Search by meaning (#287, phase 3). No sign-in, like Search in the app: a search costs ~0.01
 * neurons, the `search` zone allows 20 a minute per IP, and [VerseSearch]'s daily cap keeps the
 * Bible Helper's neurons safe. POST so the query never lands in a URL or a request log. [search]
 * is null when the AI_* settings aren't set, and every search then answers SEARCH_UNAVAILABLE.
 */
fun Route.searchRoutes(search: VerseSearch?) {
    rateLimit(SEARCH_RATE_LIMIT) {
        post("/search/semantic") {
            val request = try {
                call.receive<SemanticSearchRequest>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "The request body isn't a valid search"))
                return@post
            }
            val query = request.q.trim()
            if (query.isEmpty() || query.length > SEARCH_MAX_CHARS) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("VALIDATION_ERROR", "Invalid search", mapOf("q" to "q must be 1-$SEARCH_MAX_CHARS characters"))
                )
                return@post
            }
            if (search == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("SEARCH_UNAVAILABLE", "Search by meaning isn't set up on this server"))
                return@post
            }
            val found = try {
                search.search(query)
            } catch (e: AiProviderException) {
                // Out of neurons (the AI's or the daily cap) or Workers AI failing: the app keeps
                // its word search and says this part isn't available right now.
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("SEARCH_UNAVAILABLE", "Search by meaning isn't available right now"))
                return@post
            }
            call.respond(
                SemanticSearchResponse(
                    results = found.verses.map { SemanticSearchResult(it.book, it.chapter, it.verse, it.score) },
                    // Left out when no guide matched, so the answer is exactly what it was before #320.
                    keyPassages = found.keyPassages.ifEmpty { null }
                )
            )
        }
    }
}
