package com.application.bibleapp.server.routes

import com.application.bibleapp.server.auth.JwtIssuer
import com.application.bibleapp.server.auth.RefreshTokenIssuer
import com.application.bibleapp.server.db.tables.RefreshTokens
import com.application.bibleapp.server.models.AuthResponse
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.LoginRequest
import com.application.bibleapp.server.models.LogoutRequest
import com.application.bibleapp.server.models.RefreshRequest
import com.application.bibleapp.server.models.RegisterRequest
import com.application.bibleapp.server.models.UserResponse
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

// TODO: registered users accumulate in the shared dev Postgres across test runs since there's
// no cleanup (same known simplification as UsersTableTest). Generating a unique email per test
// invocation avoids the immediate problem (collisions with a previous run's data) without
// needing a dedicated test database yet.
class AuthRoutesTest {

    private fun uniqueRegisterRequest() = RegisterRequest(
        email = "authroutestest+${UUID.randomUUID()}@example.com",
        password = "Onezoro22@"
    )

    @Test
    fun verifyRegisterCreatesUser() = testApplication {
        environment {
            config = ApplicationConfig("application.conf")
        }

        val client = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val response = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(uniqueRegisterRequest())
        }

        assertEquals(HttpStatusCode.Created, response.status)

        val authResponse = Json.decodeFromString<AuthResponse>(response.bodyAsText())

        // A valid userId must parse as a UUID - UUID.fromString throws IllegalArgumentException
        // (which fails the test) if the string isn't one.
        UUID.fromString(authResponse.userId)

        // Verify the returned access token is a real, validly-signed JWT for this user.
        // Re-issuing a second token locally and comparing strings (the earlier approach)
        // would fail almost every run even for a correct implementation: the server signs
        // its token at one millisecond and the test would sign its own comparison token at a
        // later millisecond, so the "exp" claim - and therefore the whole signed token -
        // would practically never match byte-for-byte. The secret mirrors application.conf:
        // the dev default unless JWT_SECRET is set (as it is in CI).
        val decoded = JWT.require(Algorithm.HMAC256(System.getenv("JWT_SECRET") ?: "changeme-dev-secret"))
            .withIssuer("bibleapp-server")
            .withAudience("bibleapp-users")
            .build()
            .verify(authResponse.accessToken)

        assertEquals(authResponse.userId, decoded.subject)
        assertEquals(JwtIssuer.ACCESS_TOKEN_EXPIRES_IN_SECONDS, authResponse.accessTokenExpiresInSeconds)

        // The refresh token itself is never stored - only its hash - so the only way to prove
        // register actually persisted a session is to re-hash the raw token the same way
        // RefreshTokenIssuer does, and confirm a row with that exact hash exists.
        val storedHash = RefreshTokenIssuer.hash(authResponse.refreshToken)
        val refreshTokenRow = transaction {
            RefreshTokens.selectAll().where { RefreshTokens.tokenHash eq storedHash }.singleOrNull()
        }
        assertNotNull(refreshTokenRow, "expected a RefreshTokens row matching the returned refresh token")
        assertEquals(UUID.fromString(authResponse.userId), refreshTokenRow[RefreshTokens.userId])
    }

    @Test
    fun verifyDuplicateEmailCase() = testApplication {
        environment {
            config = ApplicationConfig("application.conf")
        }

        val client = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val request = uniqueRegisterRequest()

        val firstResponse = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        assertEquals(HttpStatusCode.Created, firstResponse.status)

        val secondResponse = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        assertEquals(HttpStatusCode.Conflict, secondResponse.status)
    }

    // Phone keyboards often capitalise the first letter, so the case someone types at sign-in
    // can differ from the case they used to create the account (#114).
    private fun mixedCaseEmail() = "AuthRoutesTest+${UUID.randomUUID()}@Example.COM"

    @Test
    fun verifySameEmailWithDifferentCaseCannotRegisterTwice() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val email = mixedCaseEmail()

        val firstResponse = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = email, password = "Onezoro22@"))
        }
        assertEquals(HttpStatusCode.Created, firstResponse.status)

        val secondResponse = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = email.lowercase(), password = "Onezoro22@"))
        }
        assertEquals(HttpStatusCode.Conflict, secondResponse.status)
        val error = Json.decodeFromString<ErrorResponse>(secondResponse.bodyAsText())
        assertEquals("EMAIL_ALREADY_REGISTERED", error.code)
    }

    @Test
    fun verifyLoginIgnoresEmailCaseAndSurroundingSpaces() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val email = mixedCaseEmail()
        val registerResponse = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = email, password = "Onezoro22@"))
        }
        assertEquals(HttpStatusCode.Created, registerResponse.status)
        val registered = Json.decodeFromString<AuthResponse>(registerResponse.bodyAsText())

        for (typed in listOf(email, email.lowercase(), email.uppercase(), "  ${email.lowercase()} ")) {
            val response = client.post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(email = typed, password = "Onezoro22@"))
            }
            assertEquals(HttpStatusCode.OK, response.status, "signing in as \"$typed\"")
            val authResponse = Json.decodeFromString<AuthResponse>(response.bodyAsText())
            assertEquals(registered.userId, authResponse.userId, "signing in as \"$typed\"")
        }
    }

    @Test
    fun verifyNonAsciiEmailsRegisterAndSignInWhateverTheirCase() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }

        // İ and Σ are where Kotlin's lowercase() and Postgres' lower() differ ("i̇" vs "i", final
        // "ς" vs "σ"): what the routes store must still pass users_email_lowercase, and sign-in
        // must lower-case the same way register did.
        val email = "ÜNİCODEΣ+${UUID.randomUUID()}@Exämple.COM"
        val registerResponse = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = email, password = "Onezoro22@"))
        }
        assertEquals(HttpStatusCode.Created, registerResponse.status)
        val registered = Json.decodeFromString<AuthResponse>(registerResponse.bodyAsText())

        for (typed in listOf(email, email.lowercase())) {
            val response = client.post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(email = typed, password = "Onezoro22@"))
            }
            assertEquals(HttpStatusCode.OK, response.status, "signing in as \"$typed\"")
            assertEquals(registered.userId, Json.decodeFromString<AuthResponse>(response.bodyAsText()).userId)
        }
    }

    @Test
    fun verifyRegisterStoresTheEmailInLowerCase() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val email = mixedCaseEmail()
        val registerResponse = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = email, password = "Onezoro22@"))
        }
        val registered = Json.decodeFromString<AuthResponse>(registerResponse.bodyAsText())

        val meResponse = client.get("/api/v1/users/me") {
            bearerAuth(registered.accessToken)
        }
        assertEquals(HttpStatusCode.OK, meResponse.status)
        assertEquals(email.lowercase(), Json.decodeFromString<UserResponse>(meResponse.bodyAsText()).email)
    }

    @Test
    fun verifyLoginWithCorrectCredentialsSucceeds() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val user = client.registerTestUser()

        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = user.email, password = user.password))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val authResponse = Json.decodeFromString<AuthResponse>(response.bodyAsText())
        assertEquals(user.auth.userId, authResponse.userId)
        // A fresh session, not the same one register created.
        assertNotEquals(user.auth.refreshToken, authResponse.refreshToken)
    }

    @Test
    fun verifyLoginWithWrongPasswordFails() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val user = client.registerTestUser()

        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = user.email, password = "the-wrong-password"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val error = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("INVALID_CREDENTIALS", error.code)
    }

    @Test
    fun verifyLoginWithUnknownEmailFailsWithTheSameErrorAsWrongPassword() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = "no-such-user+${UUID.randomUUID()}@example.com", password = "whatever123"))
        }

        // Same status and code as a wrong password - the API should never let a caller tell
        // "no such account" apart from "wrong password" (docs/api_contract.md decision 4).
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val error = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("INVALID_CREDENTIALS", error.code)
    }

    @Test
    fun verifyRefreshRotatesTheTokenAndRejectsReuseOfTheOldOne() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val user = client.registerTestUser()

        val firstRefreshResponse = client.post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken = user.auth.refreshToken))
        }
        assertEquals(HttpStatusCode.OK, firstRefreshResponse.status)
        val rotated = Json.decodeFromString<AuthResponse>(firstRefreshResponse.bodyAsText())
        assertNotEquals(user.auth.refreshToken, rotated.refreshToken)

        // Reusing the token /auth/refresh just consumed must fail - it was rotated away, and
        // reuse of an already-rotated token is treated as a possible compromise signal, not a
        // normal retry (docs/api_contract.md decision 2).
        val reuseResponse = client.post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken = user.auth.refreshToken))
        }
        assertEquals(HttpStatusCode.Unauthorized, reuseResponse.status)

        // The newly-rotated token must still work.
        val secondRefreshResponse = client.post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken = rotated.refreshToken))
        }
        assertEquals(HttpStatusCode.OK, secondRefreshResponse.status)
    }

    @Test
    fun verifyRefreshWithAnUnknownTokenFails() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken = "not-a-real-token"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun verifyLogoutRevokesThatSessionsRefreshToken() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val user = client.registerTestUser()

        val logoutResponse = client.post("/api/v1/auth/logout") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.auth.accessToken)
            setBody(LogoutRequest(refreshToken = user.auth.refreshToken))
        }
        assertEquals(HttpStatusCode.NoContent, logoutResponse.status)

        // The refresh token logout just revoked must no longer work.
        val refreshAfterLogout = client.post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken = user.auth.refreshToken))
        }
        assertEquals(HttpStatusCode.Unauthorized, refreshAfterLogout.status)
    }

    @Test
    fun verifyLogoutWithoutAnAccessTokenFails() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val user = client.registerTestUser()

        val response = client.post("/api/v1/auth/logout") {
            contentType(ContentType.Application.Json)
            setBody(LogoutRequest(refreshToken = user.auth.refreshToken))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}