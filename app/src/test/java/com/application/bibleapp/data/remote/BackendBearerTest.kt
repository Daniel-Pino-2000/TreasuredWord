package com.application.bibleapp.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The shared client's bearer auth after a session change (#86), on Ktor's MockEngine. */
class BackendBearerTest {

    private var stored: BearerTokens? = BearerTokens("token-a", "refresh-a")
    private val sentAuthorization = mutableListOf<Pair<String, String?>>()

    private val client = HttpClient(MockEngine { request ->
        sentAuthorization += request.url.host to request.headers[HttpHeaders.Authorization]
        respond("{}", HttpStatusCode.OK)
    }) {
        install(Auth) { backendBearer(backendHost = "backend.test", storedTokens = { stored }, refresh = { null }) }
    }

    @Test
    fun `after switching accounts the next request carries the new account's token`() = runTest {
        client.get("https://backend.test/api/v1/highlights")
        stored = BearerTokens("token-b", "refresh-b")
        // Ktor still has account A's token cached: this is the bug the clear below fixes.
        client.get("https://backend.test/api/v1/highlights")
        client.forgetCachedBearerTokens()
        client.get("https://backend.test/api/v1/highlights")

        assertEquals(listOf("Bearer token-a", "Bearer token-a", "Bearer token-b"), sentAuthorization.map { it.second })
    }

    @Test
    fun `after signing out no token is sent`() = runTest {
        client.get("https://backend.test/api/v1/highlights")
        stored = null
        client.forgetCachedBearerTokens()
        client.get("https://backend.test/api/v1/highlights")

        assertEquals(listOf("Bearer token-a", null), sentAuthorization.map { it.second })
    }

    @Test
    fun `other hosts never get the token`() = runTest {
        client.get("https://bible.helloao.org/api/available_translations.json")

        assertEquals(listOf("bible.helloao.org" to null), sentAuthorization)
    }
}
