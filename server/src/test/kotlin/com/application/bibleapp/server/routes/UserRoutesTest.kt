package com.application.bibleapp.server.routes

import com.application.bibleapp.server.db.tables.EmailCodePurpose
import com.application.bibleapp.server.db.tables.EmailCodes
import com.application.bibleapp.server.models.DeleteAccountRequest
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.UserResponse
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class UserRoutesTest {

    @Test
    fun verifyGetMeReturnsTheCurrentUser() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val user = client.registerTestUser()

        val response = client.get("/api/v1/users/me") {
            bearerAuth(user.auth.accessToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val userResponse = Json.decodeFromString<UserResponse>(response.bodyAsText())
        assertEquals(user.auth.userId, userResponse.id)
        assertEquals(user.email, userResponse.email)
        // Registering with a password doesn't check the email (#155).
        assertFalse(userResponse.emailVerified)
    }

    private fun insertCode(userId: UUID, purpose: String) = transaction {
        EmailCodes.insert {
            it[EmailCodes.userId] = userId
            it[EmailCodes.purpose] = purpose
            it[codeHash] = "not-a-real-hash"
            it[expiresAt] = Instant.now().plusSeconds(900)
            it[createdAt] = Instant.now()
        }
    }

    @Test
    fun deletingTheAccountDeletesItsEmailCodes() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val user = client.registerTestUser()
        val userId = UUID.fromString(user.auth.userId)
        insertCode(userId, EmailCodePurpose.VERIFY_EMAIL.dbValue)

        client.delete("/api/v1/users/me") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.auth.accessToken)
            setBody(DeleteAccountRequest(password = user.password))
        }

        // ON DELETE CASCADE in V5 (#155).
        val codesLeft = transaction { EmailCodes.selectAll().where { EmailCodes.userId eq userId }.count() }
        assertEquals(0, codesLeft)
    }

    @Test
    fun theDatabaseRejectsAnUnknownCodePurpose() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }
        val user = client.registerTestUser()

        // email_codes_purpose CHECK in V5 (#155).
        assertFailsWith<ExposedSQLException> {
            insertCode(UUID.fromString(user.auth.userId), "change_email")
        }
    }

    @Test
    fun verifyGetMeWithoutAnAccessTokenFails() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/v1/users/me")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val error = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("UNAUTHORIZED", error.code)
    }

    @Test
    fun verifyDeleteMeWithWrongPasswordFailsAndKeepsTheAccount() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val user = client.registerTestUser()

        val deleteResponse = client.delete("/api/v1/users/me") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.auth.accessToken)
            setBody(DeleteAccountRequest(password = "the-wrong-password"))
        }
        assertEquals(HttpStatusCode.Unauthorized, deleteResponse.status)

        // The account must still exist - a rejected deletion shouldn't remove anything.
        val getResponse = client.get("/api/v1/users/me") {
            bearerAuth(user.auth.accessToken)
        }
        assertEquals(HttpStatusCode.OK, getResponse.status)
    }

    @Test
    fun verifyDeleteMeWithTheCorrectPasswordRemovesTheAccount() = testApplication {
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val user = client.registerTestUser()

        val deleteResponse = client.delete("/api/v1/users/me") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.auth.accessToken)
            setBody(DeleteAccountRequest(password = user.password))
        }
        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

        // The access token is still cryptographically valid until it expires (deleting a user
        // doesn't revoke already-issued JWTs), but the user it points at is genuinely gone now,
        // so looking it up correctly reports 404 rather than pretending the account exists.
        val getResponse = client.get("/api/v1/users/me") {
            bearerAuth(user.auth.accessToken)
        }
        assertEquals(HttpStatusCode.NotFound, getResponse.status)
    }
}
