package com.application.bibleapp.server.routes

import com.application.bibleapp.server.auth.currentUserId
import com.application.bibleapp.server.db.tables.Notes
import com.application.bibleapp.server.models.CreateNoteRequest
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.NoteListResponse
import com.application.bibleapp.server.models.NoteResponse
import com.application.bibleapp.server.models.UpdateNoteRequest
import com.application.bibleapp.server.models.toJson
import com.application.bibleapp.server.models.toVerseLocations
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

private fun ResultRow.toNoteResponse() = NoteResponse(
    id = this[Notes.id].toString(),
    versionId = this[Notes.versionId],
    verses = this[Notes.versesJson].toVerseLocations(),
    text = this[Notes.content],
    createdAt = this[Notes.createdAt].toString(),
    updatedAt = this[Notes.updatedAt].toString(),
    deletedAt = this[Notes.deletedAt]?.toString()
)

fun Route.noteRoutes() {
    authenticate("auth-jwt") {

        get("/notes") {
            val userId = call.currentUserId()
            val bookIdFilter = call.request.queryParameters["bookId"]?.toIntOrNull()
            val chapterFilter = call.request.queryParameters["chapter"]?.toIntOrNull()
            val updatedSince = call.request.queryParameters["updatedSince"]
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }

            // Read before the query — see the same line in HighlightRoutes.kt (#84).
            val serverTime = Instant.now()
            val rows = transaction {
                val query = Notes.selectAll().where { Notes.userId eq userId }
                if (updatedSince != null) {
                    query.andWhere { Notes.updatedAt greaterEq updatedSince }
                } else {
                    query.andWhere { Notes.deletedAt.isNull() }
                }
                query.toList()
            }

            val filtered = rows.filter { row ->
                bookIdFilter == null || row[Notes.versesJson].toVerseLocations().any { verse ->
                    verse.bookId == bookIdFilter && (chapterFilter == null || verse.chapter == chapterFilter)
                }
            }

            call.respond(
                HttpStatusCode.OK,
                NoteListResponse(filtered.map { it.toNoteResponse() }, serverTime = serverTime.toString())
            )
        }

        // Unlike highlights, notes may be opened in a dedicated editor navigated to
        // independently, so a single-resource fetch is needed (docs/api_contract.md).
        get("/notes/{id}") {
            val userId = call.currentUserId()
            val noteId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(code = "NOT_FOUND", message = "Note not found"))
                    return@get
                }

            // Soft-deleted notes are excluded here too, consistent with the list endpoint's
            // "a plain GET only returns active items" rule - a direct fetch by id shouldn't be
            // a back door to data the list endpoint otherwise hides.
            val existing = transaction {
                Notes.selectAll()
                    .where { (Notes.id eq noteId) and (Notes.userId eq userId) }
                    .andWhere { Notes.deletedAt.isNull() }
                    .singleOrNull()
            }

            if (existing == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(code = "NOT_FOUND", message = "Note not found"))
                return@get
            }

            call.respond(HttpStatusCode.OK, existing.toNoteResponse())
        }

        post("/notes") {
            val userId = call.currentUserId()
            val request = call.receive<CreateNoteRequest>()

            val validationMessage = when {
                request.versionId.isBlank() -> "versionId must not be blank"
                request.text.isBlank() -> "text must not be blank"
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
            val noteId = transaction {
                Notes.insert {
                    it[Notes.userId] = userId
                    it[versionId] = request.versionId
                    it[versesJson] = request.verses.toJson()
                    it[content] = request.text
                    it[createdAt] = now
                    it[updatedAt] = now
                } get Notes.id
            }

            call.respond(
                HttpStatusCode.Created,
                NoteResponse(
                    id = noteId.toString(),
                    versionId = request.versionId,
                    verses = request.verses,
                    text = request.text,
                    createdAt = now.toString(),
                    updatedAt = now.toString()
                )
            )
        }

        put("/notes/{id}") {
            val userId = call.currentUserId()
            val noteId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(code = "NOT_FOUND", message = "Note not found"))
                    return@put
                }
            val request = call.receive<UpdateNoteRequest>()

            val validationMessage = if (request.text.isBlank()) "text must not be blank" else validateVerses(request.verses)
            if (validationMessage != null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(code = "VALIDATION_ERROR", message = validationMessage)
                )
                return@put
            }

            val existing = transaction {
                Notes.selectAll().where { (Notes.id eq noteId) and (Notes.userId eq userId) }.singleOrNull()
            }

            if (existing == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(code = "NOT_FOUND", message = "Note not found"))
                return@put
            }

            val now = Instant.now()
            transaction {
                Notes.update({ Notes.id eq noteId }) {
                    it[versesJson] = request.verses.toJson()
                    it[content] = request.text
                    it[updatedAt] = now
                }
            }

            call.respond(
                HttpStatusCode.OK,
                existing.toNoteResponse().copy(verses = request.verses, text = request.text, updatedAt = now.toString())
            )
        }

        delete("/notes/{id}") {
            val userId = call.currentUserId()
            val noteId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(code = "NOT_FOUND", message = "Note not found"))
                    return@delete
                }

            val existing = transaction {
                Notes.selectAll().where { (Notes.id eq noteId) and (Notes.userId eq userId) }.singleOrNull()
            }

            if (existing == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(code = "NOT_FOUND", message = "Note not found"))
                return@delete
            }

            val now = Instant.now()
            transaction {
                Notes.update({ Notes.id eq noteId }) {
                    it[deletedAt] = now
                    it[updatedAt] = now
                }
            }

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
