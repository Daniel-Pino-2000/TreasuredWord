package com.application.bibleapp.server.routes

import com.application.bibleapp.server.models.CreateHighlightRequest
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.HighlightListResponse
import com.application.bibleapp.server.models.HighlightResponse
import com.application.bibleapp.server.models.UpdateHighlightRequest
import com.application.bibleapp.server.models.VerseLocation
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HighlightRoutesTest {

    private val johnThreeSixteenToEighteen = listOf(
        VerseLocation(bookId = 43, chapter = 3, verse = 16),
        VerseLocation(bookId = 43, chapter = 3, verse = 17),
        VerseLocation(bookId = 43, chapter = 3, verse = 18)
    )

    @Test
    fun verifyCreateAndListHighlight() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val user = client.registerTestUser()

        val createResponse = client.post("/api/v1/highlights") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.auth.accessToken)
            setBody(CreateHighlightRequest(versionId = "kjv", verses = johnThreeSixteenToEighteen, color = 0xFFFFFF00.toInt()))
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val created = Json.decodeFromString<HighlightResponse>(createResponse.bodyAsText())
        assertEquals(johnThreeSixteenToEighteen, created.verses)
        assertNull(created.deletedAt)

        val listResponse = client.get("/api/v1/highlights") {
            bearerAuth(user.auth.accessToken)
        }
        assertEquals(HttpStatusCode.OK, listResponse.status)
        val list = Json.decodeFromString<HighlightListResponse>(listResponse.bodyAsText())
        assertTrue(list.highlights.any { it.id == created.id })
    }

    @Test
    fun verifyValidationRejectsAnEmptyVerseList() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val user = client.registerTestUser()

        val response = client.post("/api/v1/highlights") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.auth.accessToken)
            setBody(CreateHighlightRequest(versionId = "kjv", verses = emptyList(), color = 0))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("VALIDATION_ERROR", error.code)
    }

    @Test
    fun verifyPatchOnlyChangesTheColorNotTheVerses() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val user = client.registerTestUser()

        val created = Json.decodeFromString<HighlightResponse>(
            client.post("/api/v1/highlights") {
                contentType(ContentType.Application.Json)
                bearerAuth(user.auth.accessToken)
                setBody(CreateHighlightRequest(versionId = "kjv", verses = johnThreeSixteenToEighteen, color = 1))
            }.bodyAsText()
        )

        val patchResponse = client.patch("/api/v1/highlights/${created.id}") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.auth.accessToken)
            setBody(UpdateHighlightRequest(color = 2))
        }

        assertEquals(HttpStatusCode.OK, patchResponse.status)
        val updated = Json.decodeFromString<HighlightResponse>(patchResponse.bodyAsText())
        assertEquals(2, updated.color)
        assertEquals(created.verses, updated.verses)
        assertNotEquals(created.updatedAt, updated.updatedAt)
    }

    @Test
    fun verifyPatchOnAnUnknownIdReturnsNotFound() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val user = client.registerTestUser()

        val response = client.patch("/api/v1/highlights/${java.util.UUID.randomUUID()}") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.auth.accessToken)
            setBody(UpdateHighlightRequest(color = 1))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun verifyDeleteIsSoftAndOnlyVisibleViaUpdatedSince() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val user = client.registerTestUser()

        val created = Json.decodeFromString<HighlightResponse>(
            client.post("/api/v1/highlights") {
                contentType(ContentType.Application.Json)
                bearerAuth(user.auth.accessToken)
                setBody(CreateHighlightRequest(versionId = "kjv", verses = johnThreeSixteenToEighteen, color = 1))
            }.bodyAsText()
        )

        val deleteResponse = client.delete("/api/v1/highlights/${created.id}") {
            bearerAuth(user.auth.accessToken)
        }
        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

        val plainList = Json.decodeFromString<HighlightListResponse>(
            client.get("/api/v1/highlights") { bearerAuth(user.auth.accessToken) }.bodyAsText()
        )
        assertTrue(plainList.highlights.none { it.id == created.id })

        val sinceEpoch = Json.decodeFromString<HighlightListResponse>(
            client.get("/api/v1/highlights?updatedSince=1970-01-01T00:00:00Z") {
                bearerAuth(user.auth.accessToken)
            }.bodyAsText()
        )
        val tombstone = sinceEpoch.highlights.single { it.id == created.id }
        assertEquals(created.id, tombstone.id)
        assertTrue(tombstone.deletedAt != null)
    }

    @Test
    fun verifyBookIdFilterOnlyReturnsMatchingHighlights() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val user = client.registerTestUser()

        val johnHighlight = Json.decodeFromString<HighlightResponse>(
            client.post("/api/v1/highlights") {
                contentType(ContentType.Application.Json)
                bearerAuth(user.auth.accessToken)
                setBody(CreateHighlightRequest(versionId = "kjv", verses = johnThreeSixteenToEighteen, color = 1))
            }.bodyAsText()
        )
        client.post("/api/v1/highlights") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.auth.accessToken)
            // Genesis 1:1 - a different book entirely.
            setBody(CreateHighlightRequest(versionId = "kjv", verses = listOf(VerseLocation(1, 1, 1)), color = 2))
        }

        val filtered = Json.decodeFromString<HighlightListResponse>(
            client.get("/api/v1/highlights?bookId=43") { bearerAuth(user.auth.accessToken) }.bodyAsText()
        )

        assertEquals(1, filtered.highlights.size)
        assertEquals(johnHighlight.id, filtered.highlights.single().id)
    }

    @Test
    fun verifyListServerTimeWorksAsTheNextUpdatedSince() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val user = client.registerTestUser()

        val before = Instant.now()
        val firstPull = Json.decodeFromString<HighlightListResponse>(
            client.get("/api/v1/highlights") { bearerAuth(user.auth.accessToken) }.bodyAsText()
        )
        val serverTime = Instant.parse(firstPull.serverTime)
        assertTrue(!serverTime.isBefore(before) && !serverTime.isAfter(Instant.now()))

        // Made after the first pull, so the next pull from its serverTime must include it (#84).
        val created = Json.decodeFromString<HighlightResponse>(
            client.post("/api/v1/highlights") {
                contentType(ContentType.Application.Json)
                bearerAuth(user.auth.accessToken)
                setBody(CreateHighlightRequest(versionId = "kjv", verses = johnThreeSixteenToEighteen, color = 1))
            }.bodyAsText()
        )
        val nextPull = Json.decodeFromString<HighlightListResponse>(
            client.get("/api/v1/highlights?updatedSince=${firstPull.serverTime}") {
                bearerAuth(user.auth.accessToken)
            }.bodyAsText()
        )
        assertTrue(nextPull.highlights.any { it.id == created.id })
    }
}
