package com.application.bibleapp.server.routes

import com.application.bibleapp.server.auth.currentUserId
import com.application.bibleapp.server.db.tables.Highlights
import com.application.bibleapp.server.models.CreateHighlightRequest
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.HighlightListResponse
import com.application.bibleapp.server.models.HighlightResponse
import com.application.bibleapp.server.models.UpdateHighlightRequest
import com.application.bibleapp.server.models.toJson
import com.application.bibleapp.server.models.toVerseLocations
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

/** Maps one database row to the shape the API actually returns. */
private fun ResultRow.toHighlightResponse() = HighlightResponse(
    id = this[Highlights.id].toString(),
    versionId = this[Highlights.versionId],
    verses = this[Highlights.versesJson].toVerseLocations(),
    color = this[Highlights.color],
    createdAt = this[Highlights.createdAt].toString(),
    updatedAt = this[Highlights.updatedAt].toString(),
    deletedAt = this[Highlights.deletedAt]?.toString()
)

fun Route.highlightRoutes() {
    authenticate("auth-jwt") {

        get("/highlights") {
            val userId = call.currentUserId()
            val bookIdFilter = call.request.queryParameters["bookId"]?.toIntOrNull()
            val chapterFilter = call.request.queryParameters["chapter"]?.toIntOrNull()
            val updatedSince = call.request.queryParameters["updatedSince"]
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }

            // Read before the query, so a change saved while it runs is at or after this time and
            // the client's next `updatedSince=serverTime` still picks it up (#84).
            val serverTime = Instant.now()
            val rows = transaction {
                val query = Highlights.selectAll().where { Highlights.userId eq userId }
                if (updatedSince != null) {
                    // Include tombstones too, so a syncing device can see what was deleted.
                    query.andWhere { Highlights.updatedAt greaterEq updatedSince }
                } else {
                    query.andWhere { Highlights.deletedAt.isNull() }
                }
                query.toList()
            }

            // bookId/chapter filtering happens here, in application code, rather than in SQL:
            // verses is stored as JSON text (see models/VerseLocation.kt), not a queryable
            // jsonb column. Fine at hobby scale; a real json/child-table approach would be
            // needed to push this filter down into the database at larger scale.
            val filtered = rows.filter { row ->
                bookIdFilter == null || row[Highlights.versesJson].toVerseLocations().any { verse ->
                    verse.bookId == bookIdFilter && (chapterFilter == null || verse.chapter == chapterFilter)
                }
            }

            call.respond(
                HttpStatusCode.OK,
                HighlightListResponse(filtered.map { it.toHighlightResponse() }, serverTime = serverTime.toString())
            )
        }

        post("/highlights") {
            val userId = call.currentUserId()
            val request = call.receive<CreateHighlightRequest>()

            val validationMessage = when {
                request.versionId.isBlank() -> "versionId must not be blank"
                else -> validateVerses(request.verses)
            }
            if (validationMessage != null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(code = "VALIDATION_ERROR", message = validationMessage)
                )
                return@post
            }

            val now = Instant.now()
            val highlightId = transaction {
                Highlights.insert {
                    it[Highlights.userId] = userId
                    it[versionId] = request.versionId
                    it[versesJson] = request.verses.toJson()
                    it[color] = request.color
                    it[createdAt] = now
                    it[updatedAt] = now
                } get Highlights.id
            }

            call.respond(
                HttpStatusCode.Created,
                HighlightResponse(
                    id = highlightId.toString(),
                    versionId = request.versionId,
                    verses = request.verses,
                    color = request.color,
                    createdAt = now.toString(),
                    updatedAt = now.toString()
                )
            )
        }

        patch("/highlights/{id}") {
            val userId = call.currentUserId()
            val highlightId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(code = "NOT_FOUND", message = "Highlight not found"))
                    return@patch
                }
            val request = call.receive<UpdateHighlightRequest>()

            val existing = transaction {
                Highlights.selectAll()
                    .where { (Highlights.id eq highlightId) and (Highlights.userId eq userId) }
                    .singleOrNull()
            }

            // 404 whether the id doesn't exist at all or belongs to someone else - never a 403,
            // which would confirm to a caller that the id exists but isn't theirs.
            if (existing == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(code = "NOT_FOUND", message = "Highlight not found"))
                return@patch
            }

            val now = Instant.now()
            transaction {
                Highlights.update({ Highlights.id eq highlightId }) {
                    it[color] = request.color
                    it[updatedAt] = now
                }
            }

            call.respond(
                HttpStatusCode.OK,
                existing.toHighlightResponse().copy(color = request.color, updatedAt = now.toString())
            )
        }

        delete("/highlights/{id}") {
            val userId = call.currentUserId()
            val highlightId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(code = "NOT_FOUND", message = "Highlight not found"))
                    return@delete
                }

            val existing = transaction {
                Highlights.selectAll()
                    .where { (Highlights.id eq highlightId) and (Highlights.userId eq userId) }
                    .singleOrNull()
            }

            if (existing == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(code = "NOT_FOUND", message = "Highlight not found"))
                return@delete
            }

            // Soft delete: sets deletedAt instead of removing the row, so a syncing device that
            // asks for updatedSince changes can learn this was deleted rather than just seeing
            // it silently vanish (docs/api_contract.md decision 13).
            val now = Instant.now()
            transaction {
                Highlights.update({ Highlights.id eq highlightId }) {
                    it[deletedAt] = now
                    it[updatedAt] = now
                }
            }

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
