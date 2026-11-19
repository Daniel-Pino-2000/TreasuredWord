package com.application.bibleapp.server.routes

import com.application.bibleapp.server.models.CreateMemoryVerseRequest
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.MemoryProgress
import com.application.bibleapp.server.models.MemoryVerseListResponse
import com.application.bibleapp.server.models.MemoryVerseResponse
import com.application.bibleapp.server.models.UpdateMemoryVerseRequest
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Memorize list's endpoints (#362). Needs the database, like the other route tests. */
class MemoryVerseRoutesTest {

    private val johnThreeSixteenSeventeen = CreateMemoryVerseRequest(
        versionId = "kjv", bookId = 43, chapter = 3, verseStart = 16, verseEnd = 17
    )

    private fun learning(level: Int, at: String) =
        MemoryProgress(status = "LEARNING", level = level, nextReviewAt = "2026-12-01T00:00:00Z", correctStreak = level, progressAt = at)

    private suspend fun HttpClient.create(token: String, request: CreateMemoryVerseRequest): HttpResponse =
        post("/api/v1/memory-verses") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(request)
        }

    private suspend fun HttpClient.updateProgress(token: String, id: String, progress: MemoryProgress): MemoryVerseResponse =
        Json.decodeFromString(
            patch("/api/v1/memory-verses/$id") {
                contentType(ContentType.Application.Json)
                bearerAuth(token)
                setBody(UpdateMemoryVerseRequest(progress))
            }.bodyAsText()
        )

    @Test
    fun verifyCreateAndListMemoryVerse() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val token = client.registerTestUser().auth.accessToken

        val response = client.create(token, johnThreeSixteenSeventeen)
        assertEquals(HttpStatusCode.Created, response.status)
        val created = Json.decodeFromString<MemoryVerseResponse>(response.bodyAsText())
        assertEquals(16, created.verseStart)
        assertEquals(17, created.verseEnd)
        assertEquals("NEW", created.progress.status)
        assertEquals(0, created.progress.level)

        val list = Json.decodeFromString<MemoryVerseListResponse>(
            client.get("/api/v1/memory-verses") { bearerAuth(token) }.bodyAsText()
        )
        assertEquals(listOf(created.id), list.memoryVerses.map { it.id })
    }

    @Test
    fun verifyAPassageLongerThanSixVersesIsRefused() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val token = client.registerTestUser().auth.accessToken

        val response = client.create(token, johnThreeSixteenSeventeen.copy(verseStart = 1, verseEnd = 7))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("VALIDATION_ERROR", Json.decodeFromString<ErrorResponse>(response.bodyAsText()).code)
    }

    @Test
    fun verifyAddingTheSamePassageTwiceReturnsTheFirst() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val token = client.registerTestUser().auth.accessToken

        val first = Json.decodeFromString<MemoryVerseResponse>(client.create(token, johnThreeSixteenSeventeen).bodyAsText())
        // Another device, offline, added it too and practiced it once.
        val secondResponse = client.create(
            token,
            johnThreeSixteenSeventeen.copy(versionId = "web", progress = learning(1, "2026-11-19T10:00:00Z"))
        )

        assertEquals(HttpStatusCode.OK, secondResponse.status)
        val second = Json.decodeFromString<MemoryVerseResponse>(secondResponse.bodyAsText())
        assertEquals(first.id, second.id)
        assertEquals(1, second.progress.level)
        assertEquals("kjv", second.versionId)
    }

    @Test
    fun verifyOlderProgressNeverReplacesNewer() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val token = client.registerTestUser().auth.accessToken
        val created = Json.decodeFromString<MemoryVerseResponse>(client.create(token, johnThreeSixteenSeventeen).bodyAsText())

        val newer = client.updateProgress(token, created.id, learning(3, "2026-11-19T12:00:00Z"))
        assertEquals(3, newer.progress.level)

        // A phone that was offline since the morning sends what it had then.
        val stale = client.updateProgress(token, created.id, learning(1, "2026-11-19T08:00:00Z"))
        assertEquals(3, stale.progress.level)
        assertEquals(newer.updatedAt, stale.updatedAt)

        val list = Json.decodeFromString<MemoryVerseListResponse>(
            client.get("/api/v1/memory-verses") { bearerAuth(token) }.bodyAsText()
        )
        assertEquals(3, list.memoryVerses.single().progress.level)
    }

    @Test
    fun verifyDeleteLeavesATombstoneForSync() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val token = client.registerTestUser().auth.accessToken
        val created = Json.decodeFromString<MemoryVerseResponse>(client.create(token, johnThreeSixteenSeventeen).bodyAsText())

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/memory-verses/${created.id}") { bearerAuth(token) }.status)

        val plain = Json.decodeFromString<MemoryVerseListResponse>(
            client.get("/api/v1/memory-verses") { bearerAuth(token) }.bodyAsText()
        )
        assertTrue(plain.memoryVerses.isEmpty())
        val since = Json.decodeFromString<MemoryVerseListResponse>(
            client.get("/api/v1/memory-verses?updatedSince=1970-01-01T00:00:00Z") { bearerAuth(token) }.bodyAsText()
        )
        assertNotNull(since.memoryVerses.single().deletedAt)
    }

    @Test
    fun verifyAnotherUsersMemoryVerseIsNotFound() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val owner = client.registerTestUser().auth.accessToken
        val other = client.registerTestUser().auth.accessToken
        val created = Json.decodeFromString<MemoryVerseResponse>(client.create(owner, johnThreeSixteenSeventeen).bodyAsText())

        val patch = client.patch("/api/v1/memory-verses/${created.id}") {
            contentType(ContentType.Application.Json)
            bearerAuth(other)
            setBody(UpdateMemoryVerseRequest(learning(2, "2026-11-19T12:00:00Z")))
        }
        assertEquals(HttpStatusCode.NotFound, patch.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/memory-verses/${created.id}") { bearerAuth(other) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/memory-verses/${UUID.randomUUID()}") { bearerAuth(owner) }.status)
    }
}
