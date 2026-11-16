package com.application.bibleapp.server.routes

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.application.bibleapp.server.ai.QuotaExhausted
import com.application.bibleapp.server.ai.Unavailable
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.SemanticSearchRequest
import com.application.bibleapp.server.models.SemanticSearchResponse
import com.application.bibleapp.server.search.EmbeddingProvider
import com.application.bibleapp.server.search.FakeEmbeddingProvider
import com.application.bibleapp.server.serverModule
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `POST /api/v1/search/semantic` (#287, phase 3): no sign-in, validation, errors, the rate limit
 * and no query in the logs. The ranking itself is VerseSearchTest's.
 */
class SearchRoutesTest {

    private fun ApplicationTestBuilder.start(embedding: EmbeddingProvider? = FakeEmbeddingProvider(dimensions = 1024)): HttpClient {
        environment {
            config = ApplicationConfig("application.conf")
                .mergeWith(MapApplicationConfig().apply { put("ktor.application.modules", emptyList()) })
        }
        application { serverModule(emailSender = FakeEmailSender(), embeddingProvider = embedding) }
        return createClient { install(ContentNegotiation) { json() } }
    }

    private suspend fun HttpClient.search(q: String, ip: String = "203.0.113.7"): HttpResponse = post("/api/v1/search/semantic") {
        header("True-Client-IP", ip)
        contentType(ContentType.Application.Json)
        setBody(SemanticSearchRequest(q))
    }

    @Test
    fun searchesWithoutSigningIn() = testApplication {
        val fake = FakeEmbeddingProvider(dimensions = 1024)
        val client = start(fake)

        val response = client.search("  worry about tomorrow  ")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body<SemanticSearchResponse>().results.size <= 20)
        assertEquals(listOf("worry about tomorrow"), fake.calls.single().texts, "trimmed before it's embedded")
    }

    @Test
    fun aBlankOrTooLongQueryIs400() = testApplication {
        val fake = FakeEmbeddingProvider(dimensions = 1024)
        val client = start(fake)

        for (q in listOf("   ", "x".repeat(SEARCH_MAX_CHARS + 1))) {
            val response = client.search(q)
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("q", response.body<ErrorResponse>().fieldErrors?.keys?.single())
        }
        assertEquals(HttpStatusCode.OK, client.search("x".repeat(SEARCH_MAX_CHARS)).status)
        assertEquals(1, fake.calls.size, "a refused query never reaches the AI")
    }

    @Test
    fun notABodyIs400() = testApplication {
        val client = start()
        val response = client.post("/api/v1/search/semantic") {
            contentType(ContentType.Application.Json)
            setBody("""{"query": "worry"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun withoutTheAiSettingsItIsUnavailable() = testApplication {
        val response = start(embedding = null).search("worry")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("SEARCH_UNAVAILABLE", response.body<ErrorResponse>().code)
    }

    @Test
    fun outOfNeuronsOrAFailingAiIsUnavailable() = testApplication {
        val fake = FakeEmbeddingProvider(dimensions = 1024).apply {
            failures.addLast(QuotaExhausted("used up"))
            failures.addLast(Unavailable("HTTP 502"))
        }
        val client = start(fake)

        repeat(2) {
            val response = client.search("worry")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("SEARCH_UNAVAILABLE", response.body<ErrorResponse>().code)
        }
        assertEquals(HttpStatusCode.OK, client.search("worry").status)
    }

    @Test
    fun twentyAMinutePerIp() = testApplication {
        val client = start()

        repeat(20) { assertEquals(HttpStatusCode.OK, client.search("worry").status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.search("worry").status)
        assertEquals(HttpStatusCode.OK, client.search("worry", ip = "198.51.100.4").status, "another IP has its own 20")
    }

    @Test
    fun theQueryIsNeverLogged() = testApplication {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val logged = ListAppender<ILoggingEvent>().apply { start(); root.addAppender(this) }
        try {
            val client = start()
            client.search("a very private sorrow")
            assertFalse(logged.list.any { "private sorrow" in it.formattedMessage }, "the query must not be logged")
        } finally {
            root.detachAppender(logged)
        }
    }
}
