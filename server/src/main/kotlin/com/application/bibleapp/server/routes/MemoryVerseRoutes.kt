package com.application.bibleapp.server.routes

import com.application.bibleapp.server.auth.currentUserId
import com.application.bibleapp.server.db.dbQuery
import com.application.bibleapp.server.db.tables.MemoryVerses
import com.application.bibleapp.server.models.CreateMemoryVerseRequest
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.MemoryProgress
import com.application.bibleapp.server.models.MemoryVerseListResponse
import com.application.bibleapp.server.models.MemoryVerseResponse
import com.application.bibleapp.server.models.UpdateMemoryVerseRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

/** The longest passage the Memorize list takes (#362), so the practice games stay playable. */
const val MAX_MEMORY_PASSAGE_VERSES = 6

private val MEMORY_STATUSES = setOf("NEW", "LEARNING", "MEMORIZED")
private const val MAX_MEMORY_LEVEL = 5

private fun parseInstantOrNull(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

/**
 * Whether progress reached at [incoming] replaces what's stored, reached at [stored] (decision
 * 21): only when it's strictly newer, so an older device, or the same write sent twice, never
 * moves a passage's progress backwards. Progress with no time never replaces anything.
 */
internal fun memoryProgressReplaces(incoming: Instant?, stored: Instant?): Boolean =
    incoming != null && (stored == null || incoming.isAfter(stored))

/** The first problem with [progress], or null if it's valid. */
internal fun validateMemoryProgress(progress: MemoryProgress): String? {
    val timestamps = listOf(progress.nextReviewAt, progress.lastPracticedAt, progress.memorizedAt, progress.progressAt)
    return when {
        progress.status !in MEMORY_STATUSES -> "status must be one of ${MEMORY_STATUSES.joinToString()}"
        progress.level !in 0..MAX_MEMORY_LEVEL -> "level must be between 0 and $MAX_MEMORY_LEVEL"
        progress.correctStreak < 0 -> "correctStreak must be 0 or greater"
        timestamps.any { it != null && parseInstantOrNull(it) == null } -> "timestamps must be ISO-8601"
        else -> null
    }
}

/** The first problem with a new passage, or null if it's valid. */
internal fun validateMemoryVerse(request: CreateMemoryVerseRequest): String? = when {
    request.versionId.isBlank() -> "versionId must not be blank"
    request.bookId !in 1..66 -> "bookId must be between 1 and 66"
    request.chapter < 1 -> "chapter must be 1 or greater"
    request.verseStart < 1 -> "verseStart must be 1 or greater"
    request.verseEnd < request.verseStart -> "verseEnd must not be before verseStart"
    request.verseEnd - request.verseStart + 1 > MAX_MEMORY_PASSAGE_VERSES ->
        "a passage must have at most $MAX_MEMORY_PASSAGE_VERSES verses"
    else -> validateMemoryProgress(request.progress)
}

private fun ResultRow.toMemoryVerseResponse() = MemoryVerseResponse(
    id = this[MemoryVerses.id].toString(),
    versionId = this[MemoryVerses.versionId],
    bookId = this[MemoryVerses.bookId],
    chapter = this[MemoryVerses.chapter],
    verseStart = this[MemoryVerses.verseStart],
    verseEnd = this[MemoryVerses.verseEnd],
    progress = MemoryProgress(
        status = this[MemoryVerses.status],
        level = this[MemoryVerses.level],
        nextReviewAt = this[MemoryVerses.nextReviewAt]?.toString(),
        lastPracticedAt = this[MemoryVerses.lastPracticedAt]?.toString(),
        correctStreak = this[MemoryVerses.correctStreak],
        memorizedAt = this[MemoryVerses.memorizedAt]?.toString(),
        progressAt = this[MemoryVerses.progressAt]?.toString()
    ),
    createdAt = this[MemoryVerses.createdAt].toString(),
    updatedAt = this[MemoryVerses.updatedAt].toString(),
    deletedAt = this[MemoryVerses.deletedAt]?.toString()
)

private fun UpdateBuilder<*>.setProgress(progress: MemoryProgress) {
    this[MemoryVerses.status] = progress.status
    this[MemoryVerses.level] = progress.level
    this[MemoryVerses.nextReviewAt] = parseInstantOrNull(progress.nextReviewAt)
    this[MemoryVerses.lastPracticedAt] = parseInstantOrNull(progress.lastPracticedAt)
    this[MemoryVerses.correctStreak] = progress.correctStreak
    this[MemoryVerses.memorizedAt] = parseInstantOrNull(progress.memorizedAt)
    this[MemoryVerses.progressAt] = parseInstantOrNull(progress.progressAt)
}

private suspend fun ApplicationCall.respondValidationError(message: String) =
    respond(HttpStatusCode.BadRequest, ErrorResponse(code = "VALIDATION_ERROR", message = message))

private suspend fun ApplicationCall.respondMemoryVerseNotFound() =
    respond(HttpStatusCode.NotFound, ErrorResponse(code = "NOT_FOUND", message = "Memory verse not found"))

private fun ApplicationCall.memoryVerseIdOrNull(): UUID? =
    parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

/** The Memorize list (#362) — see docs/api_contract.md, "Memorize". Synced like highlights. */
fun Route.memoryVerseRoutes() {
    authenticate("auth-jwt") {

        get("/memory-verses") {
            val userId = call.currentUserId()
            val updatedSince = parseInstantOrNull(call.request.queryParameters["updatedSince"])

            // Read before the query, as for highlights (#84).
            val serverTime = Instant.now()
            val rows = dbQuery {
                val query = MemoryVerses.selectAll().where { MemoryVerses.userId eq userId }
                if (updatedSince != null) {
                    query.andWhere { MemoryVerses.updatedAt greaterEq updatedSince }
                } else {
                    query.andWhere { MemoryVerses.deletedAt.isNull() }
                }
                query.toList()
            }

            call.respond(
                HttpStatusCode.OK,
                MemoryVerseListResponse(rows.map { it.toMemoryVerseResponse() }, serverTime = serverTime.toString())
            )
        }

        post("/memory-verses") {
            val userId = call.currentUserId()
            val request = call.receive<CreateMemoryVerseRequest>()
            validateMemoryVerse(request)?.let {
                call.respondValidationError(it)
                return@post
            }

            val now = Instant.now()
            // The same passage added on two devices before either synced is one list entry, not
            // two: the second add returns the first, taking its progress if that's newer.
            val (row, created) = dbQuery {
                val existing = MemoryVerses.selectAll().where {
                    (MemoryVerses.userId eq userId) and (MemoryVerses.deletedAt.isNull()) and
                        (MemoryVerses.bookId eq request.bookId) and (MemoryVerses.chapter eq request.chapter) and
                        (MemoryVerses.verseStart eq request.verseStart) and (MemoryVerses.verseEnd eq request.verseEnd)
                }.firstOrNull()
                if (existing != null) {
                    val id = existing[MemoryVerses.id]
                    if (memoryProgressReplaces(parseInstantOrNull(request.progress.progressAt), existing[MemoryVerses.progressAt])) {
                        MemoryVerses.update({ MemoryVerses.id eq id }) {
                            it.setProgress(request.progress)
                            it[updatedAt] = now
                        }
                    }
                    MemoryVerses.selectAll().where { MemoryVerses.id eq id }.single() to false
                } else {
                    val id = MemoryVerses.insert {
                        it[MemoryVerses.userId] = userId
                        it[versionId] = request.versionId
                        it[bookId] = request.bookId
                        it[chapter] = request.chapter
                        it[verseStart] = request.verseStart
                        it[verseEnd] = request.verseEnd
                        it.setProgress(request.progress)
                        it[createdAt] = now
                        it[updatedAt] = now
                    } get MemoryVerses.id
                    MemoryVerses.selectAll().where { MemoryVerses.id eq id }.single() to true
                }
            }

            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, row.toMemoryVerseResponse())
        }

        patch("/memory-verses/{id}") {
            val userId = call.currentUserId()
            val id = call.memoryVerseIdOrNull() ?: return@patch call.respondMemoryVerseNotFound()
            val request = call.receive<UpdateMemoryVerseRequest>()
            validateMemoryProgress(request.progress)?.let {
                call.respondValidationError(it)
                return@patch
            }

            // A tombstone is updated like an active row, as a highlight's recolor is: the pull
            // that follows tells the device it was deleted elsewhere.
            val row = dbQuery {
                val existing = MemoryVerses.selectAll()
                    .where { (MemoryVerses.id eq id) and (MemoryVerses.userId eq userId) }
                    .singleOrNull() ?: return@dbQuery null
                // An older device's progress is ignored, not an error: the reply is what's kept.
                if (memoryProgressReplaces(parseInstantOrNull(request.progress.progressAt), existing[MemoryVerses.progressAt])) {
                    MemoryVerses.update({ MemoryVerses.id eq id }) {
                        it.setProgress(request.progress)
                        it[updatedAt] = Instant.now()
                    }
                    MemoryVerses.selectAll().where { MemoryVerses.id eq id }.single()
                } else {
                    existing
                }
            } ?: return@patch call.respondMemoryVerseNotFound()

            call.respond(HttpStatusCode.OK, row.toMemoryVerseResponse())
        }

        delete("/memory-verses/{id}") {
            val userId = call.currentUserId()
            val id = call.memoryVerseIdOrNull() ?: return@delete call.respondMemoryVerseNotFound()

            val found = dbQuery {
                val now = Instant.now()
                MemoryVerses.update({ (MemoryVerses.id eq id) and (MemoryVerses.userId eq userId) }) {
                    it[deletedAt] = now
                    it[updatedAt] = now
                } > 0
            }
            if (!found) return@delete call.respondMemoryVerseNotFound()

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
