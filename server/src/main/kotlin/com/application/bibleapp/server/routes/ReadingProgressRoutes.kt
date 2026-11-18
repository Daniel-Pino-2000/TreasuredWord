package com.application.bibleapp.server.routes

import com.application.bibleapp.server.auth.currentUserId
import com.application.bibleapp.server.db.dbQuery
import com.application.bibleapp.server.db.tables.ReadingProgress
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.ReadingProgressResponse
import com.application.bibleapp.server.models.UpdateReadingProgressRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant

private fun ResultRow.toReadingProgressResponse() = ReadingProgressResponse(
    versionId = this[ReadingProgress.versionId],
    bookId = this[ReadingProgress.bookId],
    chapter = this[ReadingProgress.chapter],
    verse = this[ReadingProgress.verse],
    updatedAt = this[ReadingProgress.updatedAt].toString()
)

/**
 * A singleton resource per user: no id, no list, just GET (may 404 if never synced) and PUT
 * (create-or-overwrite). See docs/api_contract.md decisions 7-8.
 */
fun Route.readingProgressRoutes() {
    authenticate("auth-jwt") {

        get("/reading-progress") {
            val userId = call.currentUserId()

            val row = dbQuery {
                ReadingProgress.selectAll().where { ReadingProgress.userId eq userId }.singleOrNull()
            }

            if (row == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(code = "NOT_FOUND", message = "No reading progress synced yet")
                )
                return@get
            }

            call.respond(HttpStatusCode.OK, row.toReadingProgressResponse())
        }

        put("/reading-progress") {
            val userId = call.currentUserId()
            val request = call.receive<UpdateReadingProgressRequest>()

            val readAt = runCatching { Instant.parse(request.readAt) }.getOrNull()
            val validationMessage = when {
                request.versionId.isBlank() -> "versionId must not be blank"
                request.bookId !in 1..66 -> "bookId must be between 1 and 66"
                request.chapter < 1 -> "chapter must be 1 or greater"
                request.verse < 1 -> "verse must be 1 or greater"
                readAt == null -> "readAt must be a valid ISO-8601 timestamp"
                else -> null
            }
            if (validationMessage != null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(code = "VALIDATION_ERROR", message = validationMessage)
                )
                return@put
            }
            val confirmedReadAt = readAt!!

            val existing = dbQuery {
                ReadingProgress.selectAll().where { ReadingProgress.userId eq userId }.singleOrNull()
            }

            // Stale-write guard: if the stored position is already newer than this update's
            // readAt, silently keep it instead of applying the write. This is what stops a
            // device that was offline for a while and syncs last from clobbering a newer
            // position set from another device in the meantime - not an error, the caller just
            // gets back whatever is actually current (decision 8).
            if (existing != null && existing[ReadingProgress.updatedAt].isAfter(confirmedReadAt)) {
                call.respond(HttpStatusCode.OK, existing.toReadingProgressResponse())
                return@put
            }

            dbQuery {
                if (existing == null) {
                    ReadingProgress.insert {
                        it[ReadingProgress.userId] = userId
                        it[versionId] = request.versionId
                        it[bookId] = request.bookId
                        it[chapter] = request.chapter
                        it[verse] = request.verse
                        it[updatedAt] = confirmedReadAt
                    }
                } else {
                    ReadingProgress.update({ ReadingProgress.userId eq userId }) {
                        it[versionId] = request.versionId
                        it[bookId] = request.bookId
                        it[chapter] = request.chapter
                        it[verse] = request.verse
                        it[updatedAt] = confirmedReadAt
                    }
                }
            }

            call.respond(
                HttpStatusCode.OK,
                ReadingProgressResponse(
                    versionId = request.versionId,
                    bookId = request.bookId,
                    chapter = request.chapter,
                    verse = request.verse,
                    updatedAt = confirmedReadAt.toString()
                )
            )
        }
    }
}
