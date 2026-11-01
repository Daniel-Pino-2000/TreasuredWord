package com.application.bibleapp.server.routes

import com.application.bibleapp.server.db.tables.EmailCodes
import com.application.bibleapp.server.models.AuthResponse
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.GoogleSignInRequest
import com.application.bibleapp.server.models.LoginRequest
import com.application.bibleapp.server.models.PasswordResetRequest
import com.application.bibleapp.server.models.PasswordResetSendRequest
import com.application.bibleapp.server.models.RefreshRequest
import com.application.bibleapp.server.models.UserResponse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** POST /auth/password-reset/send and /auth/password-reset (#157), with [FakeEmailSender]. */
class PasswordResetRoutesTest {

    private val newPassword = "a-brand-new-password"

    private suspend fun HttpClient.askForCode(email: String): HttpResponse =
        post("/api/v1/auth/password-reset/send") {
            contentType(ContentType.Application.Json)
            setBody(PasswordResetSendRequest(email))
        }

    private suspend fun HttpClient.reset(email: String, code: String, password: String = newPassword): HttpResponse =
        post("/api/v1/auth/password-reset") {
            contentType(ContentType.Application.Json)
            setBody(PasswordResetRequest(email, code, password))
        }

    private suspend fun HttpClient.login(email: String, password: String): HttpResponse =
        post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email, password))
        }

    private suspend fun HttpClient.refresh(refreshToken: String): HttpResponse =
        post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken))
        }

    private suspend fun HttpClient.me(accessToken: String): UserResponse =
        Json.decodeFromString(get("/api/v1/users/me") { bearerAuth(accessToken) }.bodyAsText())

    private fun errorCode(body: String) = Json.decodeFromString<ErrorResponse>(body).code

    private fun wrongCode(code: String) = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

    @Test
    fun anUnknownEmailGetsTheSameAnswerAsARealOne() = testApplication {
        val (client, email) = startWithFakeEmail()
        val user = client.registerTestUser()
        val unknown = "nobody+${UUID.randomUUID()}@example.com"

        val forUnknown = client.askForCode(unknown)
        val forReal = client.askForCode(user.email)

        assertEquals(HttpStatusCode.NoContent, forUnknown.status)
        assertEquals(forReal.status, forUnknown.status)
        assertEquals(forReal.bodyAsText(), forUnknown.bodyAsText())
        // The real account gets its code; the unknown email gets nothing.
        email.awaitCodeTo(user.email)
        delay(200)
        assertTrue(email.sentTo(unknown).isEmpty())
    }

    @Test
    fun theRightCodeChangesThePasswordAndSignsOutEveryDevice() = testApplication {
        val (client, email) = startWithFakeEmail()
        val user = client.registerTestUser()

        // Upper case finds the account, like login (#114).
        client.askForCode(user.email.uppercase())
        val code = email.awaitCodeTo(user.email)
        assertEquals(HttpStatusCode.NoContent, client.reset(user.email, code).status)

        assertEquals(HttpStatusCode.Unauthorized, client.login(user.email, user.password).status)
        val login = client.login(user.email, newPassword)
        assertEquals(HttpStatusCode.OK, login.status)
        // The session from before the reset no longer refreshes.
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(user.auth.refreshToken).status)
        // Receiving the code proved the inbox is theirs.
        val auth = Json.decodeFromString<AuthResponse>(login.bodyAsText())
        assertTrue(client.me(auth.accessToken).emailVerified)
    }

    @Test
    fun aWrongCodeChangesNothing() = testApplication {
        val (client, email) = startWithFakeEmail()
        val user = client.registerTestUser()
        client.askForCode(user.email)
        val code = email.awaitCodeTo(user.email)

        val response = client.reset(user.email, wrongCode(code))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("INVALID_CODE", errorCode(response.bodyAsText()))
        assertEquals(HttpStatusCode.OK, client.login(user.email, user.password).status)
        assertEquals(HttpStatusCode.OK, client.refresh(user.auth.refreshToken).status)
    }

    @Test
    fun anExpiredCodeDoesntReset() = testApplication {
        val (client, email) = startWithFakeEmail()
        val user = client.registerTestUser()
        client.askForCode(user.email)
        val code = email.awaitCodeTo(user.email)
        transaction {
            EmailCodes.update({ EmailCodes.userId eq UUID.fromString(user.auth.userId) }) {
                it[expiresAt] = Instant.now().minusSeconds(1)
            }
        }

        assertEquals(HttpStatusCode.BadRequest, client.reset(user.email, code).status)
        assertEquals(HttpStatusCode.OK, client.login(user.email, user.password).status)
    }

    @Test
    fun aCodeResetsOnlyOnce() = testApplication {
        val (client, email) = startWithFakeEmail()
        val user = client.registerTestUser()
        client.askForCode(user.email)
        val code = email.awaitCodeTo(user.email)
        client.reset(user.email, code)

        assertEquals(HttpStatusCode.BadRequest, client.reset(user.email, code, "yet-another-password").status)
        assertEquals(HttpStatusCode.OK, client.login(user.email, newPassword).status)
    }

    @Test
    fun afterFiveWrongTriesEvenTheRightCodeFails() = testApplication {
        val (client, email) = startWithFakeEmail()
        val user = client.registerTestUser()
        client.askForCode(user.email)
        val code = email.awaitCodeTo(user.email)

        repeat(5) { client.reset(user.email, wrongCode(code)) }

        assertEquals(HttpStatusCode.BadRequest, client.reset(user.email, code).status)
        assertEquals(HttpStatusCode.OK, client.login(user.email, user.password).status)
    }

    @Test
    fun aVerificationCodeCantResetThePassword() = testApplication {
        val (client, email) = startWithFakeEmail()
        val user = client.registerTestUser()
        client.post("/api/v1/auth/verify-email/send") { bearerAuth(user.auth.accessToken) }
        val verifyCode = email.awaitCodeTo(user.email)

        assertEquals(HttpStatusCode.BadRequest, client.reset(user.email, verifyCode).status)
        assertEquals(HttpStatusCode.OK, client.login(user.email, user.password).status)
    }

    @Test
    fun aTooShortPasswordIsRefusedWithoutSpendingATry() = testApplication {
        val (client, email) = startWithFakeEmail()
        val user = client.registerTestUser()
        client.askForCode(user.email)
        val code = email.awaitCodeTo(user.email)

        repeat(6) {
            val response = client.reset(user.email, code, "short")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_ERROR", errorCode(response.bodyAsText()))
        }
        assertEquals(HttpStatusCode.NoContent, client.reset(user.email, code).status)
    }

    @Test
    fun anUnknownEmailWithACodeGetsTheSameAnswerAsAWrongCode() = testApplication {
        val (client, _) = startWithFakeEmail()

        val response = client.reset("nobody+${UUID.randomUUID()}@example.com", "123456")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("INVALID_CODE", errorCode(response.bodyAsText()))
    }

    /** A Google-only account has no password; a reset code adds one, since the code proves the
     *  inbox is theirs just as Google did. */
    @Test
    fun aGoogleOnlyAccountCanAddAPasswordThisWay() = testApplication {
        val (client, email) = startWithFakeEmail(TestGoogle.config())
        val googleEmail = TestGoogle.newEmail()
        client.post("/api/v1/auth/google") {
            contentType(ContentType.Application.Json)
            setBody(GoogleSignInRequest(TestGoogle.idToken(TestGoogle.newSubject(), googleEmail)))
        }

        client.askForCode(googleEmail)
        val code = email.awaitCodeTo(googleEmail)
        assertEquals(HttpStatusCode.NoContent, client.reset(googleEmail, code).status)

        val login = client.login(googleEmail, newPassword)
        assertEquals(HttpStatusCode.OK, login.status)
        val me = client.me(Json.decodeFromString<AuthResponse>(login.bodyAsText()).accessToken)
        assertTrue(me.hasPassword && me.hasGoogle)
    }

    @Test
    fun withoutAnEmailProviderAskingAnswersUnavailable() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.askForCode("anyone@example.com")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("EMAIL_UNAVAILABLE", errorCode(response.bodyAsText()))
    }

    @Test
    fun askingForCodesIsInTheEmailRateLimit() = testApplication {
        val (client, _) = startWithFakeEmail()

        val statuses = (0 until 11).map { client.askForCode("nobody+${UUID.randomUUID()}@example.com").status }

        assertEquals(List(10) { HttpStatusCode.NoContent }, statuses.take(10))
        assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
    }
}
