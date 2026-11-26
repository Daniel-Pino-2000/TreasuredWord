package com.application.bibleapp.server.routes

import com.application.bibleapp.server.ai.AiProviderException
import com.application.bibleapp.server.ai.SystemPrompt
import com.application.bibleapp.server.db.dbQuery
import com.application.bibleapp.server.db.tables.SearchReports
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.SearchReportRequest
import com.application.bibleapp.server.models.SemanticSearchRequest
import com.application.bibleapp.server.models.SemanticSearchResponse
import com.application.bibleapp.server.models.SemanticSearchResult
import com.application.bibleapp.server.plugins.SEARCH_RATE_LIMIT
import com.application.bibleapp.server.plugins.SEARCH_REPORT_RATE_LIMIT
import com.application.bibleapp.server.search.VerseSearch
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import java.time.Clock

/** The longest query: a sentence, not a page, so a request can't spend much (#287). */
const val SEARCH_MAX_CHARS = 200

// A report's limits (#402): a search shows at most 20 verses, and a topic guide fewer passages.
private const val MAX_REPORT_REFERENCES = 20
private const val MAX_BIBLE_VERSION_CHARS = 40
private const val MAX_APP_VERSION_CHARS = 20

/** A USFM reference as the app shows them: "JHN 3:16", "MAT 11:28-30" or a whole chapter, "PSA 73". */
private val USFM_REFERENCE = Regex("""^[1-3]?[A-Z]{2,3} \d{1,3}(:\d{1,3}(-\d{1,3})?)?$""")

/** #402's rules for a search report: which fields are wrong and why, or an empty map. */
internal fun validateSearchReport(request: SearchReportRequest): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    val query = request.q.trim()
    if (query.isEmpty() || query.length > SEARCH_MAX_CHARS) errors["q"] = "q must be 1-$SEARCH_MAX_CHARS characters"
    if (request.language !in SystemPrompt.languageNames) {
        errors["language"] = "language must be one of ${SystemPrompt.languageNames.keys.joinToString(", ")}"
    }
    if (request.bibleVersion.isBlank() || request.bibleVersion.length > MAX_BIBLE_VERSION_CHARS) {
        errors["bibleVersion"] = "bibleVersion must be 1-$MAX_BIBLE_VERSION_CHARS characters"
    }
    if ((request.appVersion?.length ?: 0) > MAX_APP_VERSION_CHARS) {
        errors["appVersion"] = "appVersion must be at most $MAX_APP_VERSION_CHARS characters"
    }
    for ((field, references) in listOf("results" to request.results, "keyPassages" to request.keyPassages)) {
        when {
            references.size > MAX_REPORT_REFERENCES -> errors[field] = "$field must have at most $MAX_REPORT_REFERENCES references"
            references.any { !USFM_REFERENCE.matches(it) } -> errors[field] = "$field must be USFM references like \"JHN 3:16\""
        }
    }
    return errors
}

/**
 * Search by meaning (#287, phase 3). No sign-in, like Search in the app: a search costs ~0.01
 * neurons, the `search` zone allows 20 a minute per IP, and [VerseSearch]'s daily cap keeps the
 * Bible Helper's neurons safe. POST so the query never lands in a URL or a request log. [search]
 * is null when the AI_* settings aren't set, and every search then answers SEARCH_UNAVAILABLE.
 *
 * `/search/reports` (#402) stores a search whose results didn't help, sent only when the person
 * taps Send: no sign-in, 10 an hour per IP (the `search-report` zone), stored without the user or
 * the IP, and kept [REPORT_RETENTION] like the Bible Helper's reports. [clock] dates them.
 */
fun Route.searchRoutes(search: VerseSearch?, clock: Clock) {
    rateLimit(SEARCH_REPORT_RATE_LIMIT) {
        post("/search/reports") {
            val request = try {
                call.receive<SearchReportRequest>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "The request body isn't a valid report"))
                return@post
            }
            val fieldErrors = validateSearchReport(request)
            if (fieldErrors.isNotEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("VALIDATION_ERROR", "Invalid report", fieldErrors))
                return@post
            }
            val now = clock.instant()
            dbQuery {
                SearchReports.insert {
                    it[createdAt] = now
                    it[query] = request.q.trim()
                    it[language] = request.language
                    it[bibleVersion] = request.bibleVersion
                    it[appVersion] = request.appVersion?.takeIf { version -> version.isNotBlank() }
                    it[results] = request.results.joinToString(", ")
                    it[keyPassages] = request.keyPassages.joinToString(", ")
                }
                // Like the Bible Helper's reports: the 90-day cleanup on each insert, and
                // db/RetentionCleanup.kt for when none comes in.
                SearchReports.deleteWhere { createdAt less now.minus(REPORT_RETENTION) }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }

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
                    keyPassages = found.keyPassages.ifEmpty { null },
                    // Same: only `true` is ever sent (#324).
                    crisis = true.takeIf { found.crisis },
                    // Same (#401).
                    suggestAsk = true.takeIf { found.suggestAsk }
                )
            )
        }
    }
}
