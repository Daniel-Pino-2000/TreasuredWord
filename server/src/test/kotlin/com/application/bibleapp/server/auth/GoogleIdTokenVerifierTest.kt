package com.application.bibleapp.server.auth

import com.application.bibleapp.server.routes.TestGoogle
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Each check a Google ID token must pass before its email is trusted (#112). No database. */
class GoogleIdTokenVerifierTest {

    private val verifier = GoogleIdTokenVerifier(TestGoogle.CLIENT_ID, TestGoogle.keyProvider)
    private val subject = TestGoogle.newSubject()
    private val email = TestGoogle.newEmail()

    @Test
    fun aValidTokenGivesTheGoogleAccount() {
        val identity = verifier.verify(TestGoogle.idToken(subject, email))
        assertEquals(GoogleIdentity(subject, email), identity)
    }

    @Test
    fun theShortIssuerGoogleAlsoUsesIsAccepted() {
        val identity = verifier.verify(TestGoogle.idToken(subject, email, issuer = "accounts.google.com"))
        assertEquals(subject, identity?.subject)
    }

    @Test
    fun anExpiredTokenIsRejected() {
        val token = TestGoogle.idToken(subject, email, expiresAt = Instant.now().minusSeconds(3600))
        assertNull(verifier.verify(token))
    }

    @Test
    fun aTokenForAnotherAppIsRejected() {
        val token = TestGoogle.idToken(subject, email, audience = "someone-elses-app.apps.googleusercontent.com")
        assertNull(verifier.verify(token))
    }

    @Test
    fun aTokenNotIssuedByGoogleIsRejected() {
        val token = TestGoogle.idToken(subject, email, issuer = "https://evil.example.com")
        assertNull(verifier.verify(token))
    }

    @Test
    fun aTokenSignedWithAnotherKeyIsRejected() {
        val token = TestGoogle.idToken(subject, email, signingKey = TestGoogle.newKeyPair())
        assertNull(verifier.verify(token))
    }

    @Test
    fun anUnverifiedEmailIsRejected() {
        assertNull(verifier.verify(TestGoogle.idToken(subject, email, emailVerified = false)))
    }

    @Test
    fun somethingThatIsntATokenIsRejected() {
        assertNull(verifier.verify("not-a-token"))
    }
}
