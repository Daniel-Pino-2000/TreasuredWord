package com.application.bibleapp.server.plugins

import com.application.bibleapp.server.models.LoginRequest
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** The `auth` rate-limit zone is per caller IP, not one limit for the whole server (#162). */
class RateLimitingTest {

    private fun ApplicationTestBuilder.jsonClient(): HttpClient {
        environment { config = ApplicationConfig("application.conf") }
        return createClient { install(ContentNegotiation) { json() } }
    }

    /** A login that fails (no such account), from [ip] as Render's proxy would report it. The
     *  failure doesn't matter: every request counts against the limit. */
    private suspend fun HttpClient.loginFrom(ip: String, header: String = "X-Forwarded-For"): HttpStatusCode =
        post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            header(header, ip)
            setBody(LoginRequest("nobody+${UUID.randomUUID()}@example.com", "wrong-password"))
        }.status

    @Test
    fun oneIpIsLimitedToTenAMinute() = testApplication {
        val client = jsonClient()

        val statuses = List(11) { client.loginFrom("203.0.113.1") }

        assertEquals(List(10) { HttpStatusCode.Unauthorized }, statuses.take(10))
        assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
    }

    @Test
    fun anotherIpIsNotHeldBackByTheFirstOnesLimit() = testApplication {
        val client = jsonClient()
        repeat(11) { client.loginFrom("203.0.113.1") }

        // Before #162 everyone shared one limit, so this got 429 too.
        assertEquals(HttpStatusCode.Unauthorized, client.loginFrom("198.51.100.7"))
        assertEquals(HttpStatusCode.TooManyRequests, client.loginFrom("203.0.113.1"))
    }

    @Test
    fun cloudflaresTrueClientIpIsTheKeyWhenPresent() = testApplication {
        val client = jsonClient()
        repeat(10) { client.loginFrom("203.0.113.1", header = "True-Client-IP") }

        assertEquals(HttpStatusCode.TooManyRequests, client.loginFrom("203.0.113.1", header = "True-Client-IP"))
        assertEquals(HttpStatusCode.Unauthorized, client.loginFrom("198.51.100.7", header = "True-Client-IP"))
    }
}
