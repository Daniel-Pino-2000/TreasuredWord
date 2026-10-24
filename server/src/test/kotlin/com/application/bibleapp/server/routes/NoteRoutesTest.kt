package com.application.bibleapp.server.routes

import com.application.bibleapp.server.models.CreateNoteRequest
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.NoteListResponse
import com.application.bibleapp.server.models.NoteResponse
import com.application.bibleapp.server.models.UpdateNoteRequest
import com.application.bibleapp.server.models.VerseLocation
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteRoutesTest {

    private val johnThreeSixteen = listOf(VerseLocation(bookId = 43, chapter = 3, verse = 16))

    @Test
    fun verifyCreateGetAndListNote() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val user = client.registerTestUser()

        val createResponse = client.post("/api/v1/notes") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.auth.accessToken)
            setBody(CreateNoteRequest(versionId = "kjv", verses = johnThreeSixteen, text = "For God so loved the world"))
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val created = Json.decodeFromString<NoteResponse>(createResponse.bodyAsText())

        val getResponse = client.get("/api/v1/notes/${created.id}") {
            bearerAuth(user.auth.accessToken)
        }
        assertEquals(HttpStatusCode.OK, getResponse.status)
        assertEquals(created.text, Json.decodeFromString<NoteResponse>(getResponse.bodyAsText()).text)

        val listResponse = Json.decodeFromString<NoteListResponse>(
            client.get("/api/v1/notes") { bearerAuth(user.auth.accessToken) }.bodyAsText()
        )
        assertTrue(listResponse.notes.any { it.id == created.id })
    }

    @Test
    fun verifyGetOnAnUnknownIdReturnsNotFound() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val user = client.registerTestUser()

        val response = client.get("/api/v1/notes/${UUID.randomUUID()}") {
            bearerAuth(user.auth.accessToken)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun verifyValidationRejectsBlankText() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val user = client.registerTestUser()

        val response = client.post("/api/v1/notes") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.auth.accessToken)
            setBody(CreateNoteRequest(versionId = "kjv", verses = johnThreeSixteen, text = "   "))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("VALIDATION_ERROR", error.code)
    }

    @Test
    fun verifyPutUpdatesBothTextAndVerses() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val user = client.registerTestUser()

        val created = Json.decodeFromString<NoteResponse>(
            client.post("/api/v1/notes") {
                contentType(ContentType.Application.Json)
                bearerAuth(user.auth.accessToken)
                setBody(CreateNoteRequest(versionId = "kjv", verses = johnThreeSixteen, text = "original"))
            }.bodyAsText()
        )

        val newVerses = johnThreeSixteen + VerseLocation(bookId = 43, chapter = 3, verse = 17)
        val putResponse = client.put("/api/v1/notes/${created.id}") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.auth.accessToken)
            setBody(UpdateNoteRequest(verses = newVerses, text = "revised"))
        }

        assertEquals(HttpStatusCode.OK, putResponse.status)
        val updated = Json.decodeFromString<NoteResponse>(putResponse.bodyAsText())
        assertEquals("revised", updated.text)
        assertEquals(newVerses, updated.verses)
    }

    @Test
    fun verifyDeleteIsSoftDelete() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val user = client.registerTestUser()

        val created = Json.decodeFromString<NoteResponse>(
            client.post("/api/v1/notes") {
                contentType(ContentType.Application.Json)
                bearerAuth(user.auth.accessToken)
                setBody(CreateNoteRequest(versionId = "kjv", verses = johnThreeSixteen, text = "to be deleted"))
            }.bodyAsText()
        )

        val deleteResponse = client.delete("/api/v1/notes/${created.id}") {
            bearerAuth(user.auth.accessToken)
        }
        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

        // Soft-deleted notes are still a real row (see UpdatedSince-based sync, tested on
        // Highlights), but a direct GET by id treats them the same as "not found", matching
        // the list endpoint's "only active items" rule.
        val getAfterDelete = client.get("/api/v1/notes/${created.id}") {
            bearerAuth(user.auth.accessToken)
        }
        assertEquals(HttpStatusCode.NotFound, getAfterDelete.status)

        val listResponse = Json.decodeFromString<NoteListResponse>(
            client.get("/api/v1/notes") { bearerAuth(user.auth.accessToken) }.bodyAsText()
        )
        assertTrue(listResponse.notes.none { it.id == created.id })
    }

    @Test
    fun verifyListServerTimeWorksAsTheNextUpdatedSince() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val user = client.registerTestUser()

        val before = Instant.now()
        val firstPull = Json.decodeFromString<NoteListResponse>(
            client.get("/api/v1/notes") { bearerAuth(user.auth.accessToken) }.bodyAsText()
        )
        val serverTime = Instant.parse(firstPull.serverTime)
        assertTrue(!serverTime.isBefore(before) && !serverTime.isAfter(Instant.now()))

        // Made after the first pull, so the next pull from its serverTime must include it (#84).
        val created = Json.decodeFromString<NoteResponse>(
            client.post("/api/v1/notes") {
                contentType(ContentType.Application.Json)
                bearerAuth(user.auth.accessToken)
                setBody(CreateNoteRequest(versionId = "kjv", verses = johnThreeSixteen, text = "Later"))
            }.bodyAsText()
        )
        val nextPull = Json.decodeFromString<NoteListResponse>(
            client.get("/api/v1/notes?updatedSince=${firstPull.serverTime}") {
                bearerAuth(user.auth.accessToken)
            }.bodyAsText()
        )
        assertTrue(nextPull.notes.any { it.id == created.id })
    }
}
