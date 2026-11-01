package com.application.bibleapp.server.routes

import com.application.bibleapp.server.db.tables.EmailCodes
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.UserResponse
import com.application.bibleapp.server.models.VerifyEmailRequest
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
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** POST /auth/verify-email/send and /auth/verify-email (#156), with [FakeEmailSender]. */
class VerifyEmailRoutesTest {

    private suspend fun HttpClient.sendCode(user: TestUser): HttpResponse =
        post("/api/v1/auth/verify-email/send") { bearerAuth(user.auth.accessToken) }

    private suspend fun HttpClient.verify(user: TestUser, code: String): HttpResponse =
        post("/api/v1/auth/verify-email") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.auth.accessToken)
            setBody(VerifyEmailRequest(code))
        }

    private suspend fun HttpClient.isVerified(user: TestUser): Boolean =
        Json.decodeFromString<UserResponse>(get("/api/v1/users/me") { bearerAuth(user.auth.accessToken) }.bodyAsText()).emailVerified

    private fun errorCode(body: String) = Json.decodeFromString<ErrorResponse>(body).code

    /** Any 6-digit code other than [code]. */
    private fun wrongCode(code: String) = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

    @Test
    fun theEmailedCodeVerifiesTheEmail() = testApplication {
        val (client, email) = startWithFakeEmail()
        val user = client.registerTestUser()

        assertEquals(HttpStatusCode.NoContent, client.sendCode(user).status)
        val message = email.sentTo(user.email).single()
        val code = email.awaitCodeTo(user.email)
        assertTrue(code in message.subject, "the code is in the subject, readable from the notification")

        // Pasted with spaces still works.
        assertEquals(HttpStatusCode.NoContent, client.verify(user, " ${code.take(3)} ${code.drop(3)} ").status)
        assertTrue(client.isVerified(user))
    }

    @Test
    fun aWrongCodeDoesNotVerify() = testApplication {
        val (client, email) = startWithFakeEmail()
        val user = client.registerTestUser()
        client.sendCode(user)
        val code = email.awaitCodeTo(user.email)

        val response = client.verify(user, wrongCode(code))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("INVALID_CODE", errorCode(response.bodyAsText()))
        assertFalse(client.isVerified(user))
    }

    @Test
    fun anExpiredCodeDoesNotVerify() = testApplication {
        val (client, email) = startWithFakeEmail()
        val user = client.registerTestUser()
        client.sendCode(user)
        val code = email.awaitCodeTo(user.email)
        transaction {
            EmailCodes.update({ EmailCodes.userId eq UUID.fromString(user.auth.userId) }) {
                it[expiresAt] = Instant.now().minusSeconds(1)
            }
        }

        assertEquals(HttpStatusCode.BadRequest, client.verify(user, code).status)
        assertFalse(client.isVerified(user))
    }

    @Test
    fun aCodeWorksOnlyOnce() = testApplication {
        val (client, email) = startWithFakeEmail()
        val user = client.registerTestUser()
        client.sendCode(user)
        val code = email.awaitCodeTo(user.email)
        client.verify(user, code)

        val usedAt = transaction {
            EmailCodes.selectAll().where { EmailCodes.userId eq UUID.fromString(user.auth.userId) }.single()[EmailCodes.usedAt]
        }
        assertNotEquals(null, usedAt)
        // Verified accounts can't ask for another code...
        val again = client.sendCode(user)
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("EMAIL_ALREADY_VERIFIED", errorCode(again.bodyAsText()))
        // ...and checking again (a second tap) just says it's done.
        assertEquals(HttpStatusCode.NoContent, client.verify(user, code).status)
    }

    @Test
    fun aUsedCodeCantBeUsedAgain() = testApplication {
        val (client, email) = startWithFakeEmail()
        val user = client.registerTestUser()
        client.sendCode(user)
        val code = email.awaitCodeTo(user.email)
        // Spend the code without verifying, as a password reset would spend its own.
        transaction {
            EmailCodes.update({ EmailCodes.userId eq UUID.fromString(user.auth.userId) }) { it[usedAt] = Instant.now() }
        }

        assertEquals(HttpStatusCode.BadRequest, client.verify(user, code).status)
        assertFalse(client.isVerified(user))
    }

    @Test
    fun afterFiveWrongTriesEvenTheRightCodeFails() = testApplication {
        val (client, email) = startWithFakeEmail()
        val user = client.registerTestUser()
        client.sendCode(user)
        val code = email.awaitCodeTo(user.email)

        repeat(5) { assertEquals(HttpStatusCode.BadRequest, client.verify(user, wrongCode(code)).status) }
        val response = client.verify(user, code)

        // The same answer as a wrong code, so a guesser can't tell it ran out.
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("INVALID_CODE", errorCode(response.bodyAsText()))
        assertFalse(client.isVerified(user))
    }

    @Test
    fun aNewCodeCancelsTheOldOne() = testApplication {
        val (client, email) = startWithFakeEmail()
        val user = client.registerTestUser()
        client.sendCode(user)
        val first = email.awaitCodeTo(user.email)
        client.sendCode(user)
        val second = CODE.find(email.sentTo(user.email).last().text)!!.value

        if (first != second) assertEquals(HttpStatusCode.BadRequest, client.verify(user, first).status)
        assertEquals(HttpStatusCode.NoContent, client.verify(user, second).status)
    }

    @Test
    fun oneAccountGetsAtMostFiveCodesAnHour() = testApplication {
        val (client, email) = startWithFakeEmail()
        val user = client.registerTestUser()

        repeat(5) { assertEquals(HttpStatusCode.NoContent, client.sendCode(user).status) }
        val sixth = client.sendCode(user)

        assertEquals(HttpStatusCode.TooManyRequests, sixth.status)
        assertEquals("TOO_MANY_EMAILS", errorCode(sixth.bodyAsText()))
        assertEquals(5, email.sentTo(user.email).size)
    }

    @Test
    fun oneIpGetsAtMostTenEmailsAnHour() = testApplication {
        val (client, email) = startWithFakeEmail()
        // Three accounts, so the per-account limit (5) isn't what stops it.
        val users = List(3) { client.registerTestUser() }

        val statuses = (0 until 11).map { client.sendCode(users[it % 3]).status }

        assertEquals(List(10) { HttpStatusCode.NoContent }, statuses.take(10))
        assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
        assertEquals(10, email.sent.size)
    }

    @Test
    fun ifTheEmailCantBeSentTheAppIsToldAndTheCodeDoesntCount() = testApplication {
        val (client, email) = startWithFakeEmail()
        val user = client.registerTestUser()
        email.failing = true

        val response = client.sendCode(user)

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("EMAIL_UNAVAILABLE", errorCode(response.bodyAsText()))
        val codesStored = transaction { EmailCodes.selectAll().where { EmailCodes.userId eq UUID.fromString(user.auth.userId) }.count() }
        assertEquals(0, codesStored)
    }

    @Test
    fun withoutAnEmailProviderSendingAnswersUnavailable() = testApplication {
        // application.conf alone, as on a server where RESEND_API_KEY isn't set.
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val user = client.registerTestUser()

        val response = client.sendCode(user)

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("EMAIL_UNAVAILABLE", errorCode(response.bodyAsText()))
    }

    @Test
    fun bothEndpointsNeedASignedInUser() = testApplication {
        val (client, _) = startWithFakeEmail()

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/verify-email/send").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/v1/auth/verify-email") {
                contentType(ContentType.Application.Json)
                setBody(VerifyEmailRequest("123456"))
            }.status
        )
    }

    private companion object {
        val CODE = Regex("\\b\\d{6}\\b")
    }
}
