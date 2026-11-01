package com.application.bibleapp.data.repository

import com.application.bibleapp.data.remote.BackendErrorResponseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the server's failures for emailed codes become messages (#159, #160). */
class AuthErrorMappingTest {

    private fun body(code: String) = BackendErrorResponseDto(code = code, message = "server text")

    @Test
    fun `each code error gets its own message`() {
        assertEquals(AuthError.InvalidCode, authErrorFor(400, body("INVALID_CODE")))
        assertEquals(AuthError.TooManyEmails, authErrorFor(429, body("TOO_MANY_EMAILS"), sendsEmail = true))
        assertEquals(AuthError.EmailUnavailable, authErrorFor(503, body("EMAIL_UNAVAILABLE"), sendsEmail = true))
        assertEquals(AuthError.EmailAlreadyVerified, authErrorFor(409, body("EMAIL_ALREADY_VERIFIED"), sendsEmail = true))
    }

    @Test
    fun `a rate limit with no body is the email limit only where email is sent`() {
        assertEquals(AuthError.TooManyEmails, authErrorFor(429, null, sendsEmail = true))
        assertEquals(AuthError.TooManyAttempts, authErrorFor(429, null))
    }

    @Test
    fun `a server without the email endpoints says email is unavailable`() {
        assertEquals(AuthError.EmailUnavailable, authErrorFor(404, null, sendsEmail = true))
        assertTrue(authErrorFor(404, null) is AuthError.Unknown)
    }

    @Test
    fun `the existing codes still map as before`() {
        assertEquals(AuthError.InvalidCredentials, authErrorFor(401, body("INVALID_CREDENTIALS")))
        assertEquals(AuthError.EmailAlreadyRegistered, authErrorFor(409, body("EMAIL_ALREADY_REGISTERED")))
        assertEquals(AuthError.GoogleUnavailable, authErrorFor(503, body("GOOGLE_SIGN_IN_UNAVAILABLE")))
        assertTrue(authErrorFor(500, body("INTERNAL_ERROR")) is AuthError.Unknown)
    }
}
