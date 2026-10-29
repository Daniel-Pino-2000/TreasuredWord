package com.application.bibleapp.server.routes

import com.application.bibleapp.server.db.tables.Users
import com.application.bibleapp.server.models.AuthResponse
import com.application.bibleapp.server.models.DeleteAccountRequest
import com.application.bibleapp.server.models.ErrorResponse
import com.application.bibleapp.server.models.GoogleSignInRequest
import com.application.bibleapp.server.models.LoginRequest
import com.application.bibleapp.server.models.RefreshRequest
import com.application.bibleapp.server.models.UserResponse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** POST /auth/google and deleting a Google account (#112), against [TestGoogle]'s stand-in. */
class GoogleAuthRoutesTest {

    private fun ApplicationTestBuilder.googleClient(): HttpClient {
        environment { config = TestGoogle.config() }
        return createClient { install(ContentNegotiation) { json() } }
    }

    private suspend fun HttpClient.signInWithGoogle(idToken: String): HttpResponse =
        post("/api/v1/auth/google") {
            contentType(ContentType.Application.Json)
            setBody(GoogleSignInRequest(idToken))
        }

    private suspend fun HttpClient.refresh(refreshToken: String): HttpResponse =
        post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken))
        }

    private suspend fun HttpClient.me(accessToken: String): UserResponse =
        Json.decodeFromString(get("/api/v1/users/me") { bearerAuth(accessToken) }.bodyAsText())

    private fun userRow(id: String) = transaction {
        Users.selectAll().where { Users.id eq UUID.fromString(id) }.single()
    }

    @Test
    fun aNewGoogleAccountCreatesAnAccountWithoutAPassword() = testApplication {
        val client = googleClient()
        val subject = TestGoogle.newSubject()
        val email = TestGoogle.newEmail()

        val response = client.signInWithGoogle(TestGoogle.idToken(subject, email))

        assertEquals(HttpStatusCode.Created, response.status)
        val auth = Json.decodeFromString<AuthResponse>(response.bodyAsText())
        val row = userRow(auth.userId)
        assertEquals(email, row[Users.email])
        assertEquals(subject, row[Users.googleSubject])
        assertNull(row[Users.passwordHash])

        val me = client.me(auth.accessToken)
        assertFalse(me.hasPassword)
        assertTrue(me.hasGoogle)
    }

    @Test
    fun signingInAgainWithTheSameGoogleAccountReturnsTheSameAccount() = testApplication {
        val client = googleClient()
        val subject = TestGoogle.newSubject()
        val email = TestGoogle.newEmail()

        val first = Json.decodeFromString<AuthResponse>(client.signInWithGoogle(TestGoogle.idToken(subject, email)).bodyAsText())
        val again = client.signInWithGoogle(TestGoogle.idToken(subject, email))

        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(first.userId, Json.decodeFromString<AuthResponse>(again.bodyAsText()).userId)
    }

    @Test
    fun theGoogleAccountIsFoundByItsIdEvenAfterItsEmailChanges() = testApplication {
        val client = googleClient()
        val subject = TestGoogle.newSubject()

        val first = Json.decodeFromString<AuthResponse>(client.signInWithGoogle(TestGoogle.idToken(subject, TestGoogle.newEmail())).bodyAsText())
        val again = client.signInWithGoogle(TestGoogle.idToken(subject, TestGoogle.newEmail()))

        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(first.userId, Json.decodeFromString<AuthResponse>(again.bodyAsText()).userId)
    }

    /**
     * Registering never checks that the email is the person's, so whoever set the password may
     * not own the inbox. Google has checked it, so linking hands the account to the Google
     * user: the password and every existing session stop working (#142, "pre-hijacking").
     */
    @Test
    fun linkingAPasswordAccountRemovesThePasswordAndItsSessions() = testApplication {
        val client = googleClient()
        val passwordUser = client.registerTestUser()
        val subject = TestGoogle.newSubject()

        // Upper case from Google still finds the lower-case account (#114).
        val response = client.signInWithGoogle(TestGoogle.idToken(subject, passwordUser.email.uppercase()))

        // Linked, not duplicated.
        assertEquals(HttpStatusCode.OK, response.status)
        val auth = Json.decodeFromString<AuthResponse>(response.bodyAsText())
        assertEquals(passwordUser.auth.userId, auth.userId)
        assertEquals(subject, userRow(auth.userId)[Users.googleSubject])

        val me = client.me(auth.accessToken)
        assertFalse(me.hasPassword)
        assertTrue(me.hasGoogle)

        // The password no longer signs in.
        val login = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(passwordUser.email, passwordUser.password))
        }
        assertEquals(HttpStatusCode.Unauthorized, login.status)

        // The session from before the link is revoked...
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(passwordUser.auth.refreshToken).status)
        // ...but not the one the Google sign-in just started.
        assertEquals(HttpStatusCode.OK, client.refresh(auth.refreshToken).status)
    }

    @Test
    fun anAccountLinkedToAnotherGoogleAccountIsNotTakenOver() = testApplication {
        val client = googleClient()
        val email = TestGoogle.newEmail()
        client.signInWithGoogle(TestGoogle.idToken(TestGoogle.newSubject(), email))

        val response = client.signInWithGoogle(TestGoogle.idToken(TestGoogle.newSubject(), email))

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("EMAIL_LINKED_TO_OTHER_GOOGLE_ACCOUNT", Json.decodeFromString<ErrorResponse>(response.bodyAsText()).code)
    }

    @Test
    fun aTokenThatFailsTheChecksIsRejected() = testApplication {
        val client = googleClient()
        val token = TestGoogle.idToken(TestGoogle.newSubject(), TestGoogle.newEmail(), audience = "another-app")

        val response = client.signInWithGoogle(token)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("INVALID_GOOGLE_TOKEN", Json.decodeFromString<ErrorResponse>(response.bodyAsText()).code)
    }

    @Test
    fun withoutAGoogleClientIdTheServerSaysGoogleSignInIsUnavailable() = testApplication {
        // application.conf alone, as on a server where GOOGLE_CLIENT_ID isn't set.
        environment { config = ApplicationConfig("application.conf") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.signInWithGoogle(TestGoogle.idToken(TestGoogle.newSubject(), TestGoogle.newEmail()))

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("GOOGLE_SIGN_IN_UNAVAILABLE", Json.decodeFromString<ErrorResponse>(response.bodyAsText()).code)
    }

    @Test
    fun aGoogleOnlyAccountCantBeSignedInToWithAPassword() = testApplication {
        val client = googleClient()
        val email = TestGoogle.newEmail()
        client.signInWithGoogle(TestGoogle.idToken(TestGoogle.newSubject(), email))

        val login = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email, "any-password"))
        }

        assertEquals(HttpStatusCode.Unauthorized, login.status)
        assertEquals("INVALID_CREDENTIALS", Json.decodeFromString<ErrorResponse>(login.bodyAsText()).code)
    }

    @Test
    fun aGoogleOnlyAccountDeletesItselfBySigningInWithGoogleAgain() = testApplication {
        val client = googleClient()
        val subject = TestGoogle.newSubject()
        val email = TestGoogle.newEmail()
        val auth = Json.decodeFromString<AuthResponse>(client.signInWithGoogle(TestGoogle.idToken(subject, email)).bodyAsText())

        val delete = client.delete("/api/v1/users/me") {
            contentType(ContentType.Application.Json)
            bearerAuth(auth.accessToken)
            setBody(DeleteAccountRequest(googleIdToken = TestGoogle.idToken(subject, email)))
        }

        assertEquals(HttpStatusCode.NoContent, delete.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/users/me") { bearerAuth(auth.accessToken) }.status)
    }

    @Test
    fun deletingWithADifferentGoogleAccountIsRefusedAndKeepsTheAccount() = testApplication {
        val client = googleClient()
        val auth = Json.decodeFromString<AuthResponse>(
            client.signInWithGoogle(TestGoogle.idToken(TestGoogle.newSubject(), TestGoogle.newEmail())).bodyAsText()
        )

        val delete = client.delete("/api/v1/users/me") {
            contentType(ContentType.Application.Json)
            bearerAuth(auth.accessToken)
            setBody(DeleteAccountRequest(googleIdToken = TestGoogle.idToken(TestGoogle.newSubject(), TestGoogle.newEmail())))
        }

        assertEquals(HttpStatusCode.Unauthorized, delete.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/users/me") { bearerAuth(auth.accessToken) }.status)
    }

    @Test
    fun deletingWithNeitherPasswordNorGoogleIsAValidationError() = testApplication {
        val client = googleClient()
        val user = client.registerTestUser()

        val delete = client.delete("/api/v1/users/me") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.auth.accessToken)
            setBody(DeleteAccountRequest())
        }

        assertEquals(HttpStatusCode.BadRequest, delete.status)
    }
}
