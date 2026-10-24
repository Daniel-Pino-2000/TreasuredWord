package com.application.bibleapp.data.repository

import android.content.Context
import com.application.bibleapp.data.local.TokenStore
import com.application.bibleapp.data.remote.AuthResponseDto
import com.application.bibleapp.data.remote.BackendErrorResponseDto
import com.application.bibleapp.data.remote.DeleteAccountRequestDto
import com.application.bibleapp.data.remote.HttpClientProvider
import com.application.bibleapp.data.remote.LoginRequestDto
import com.application.bibleapp.data.remote.LogoutRequestDto
import com.application.bibleapp.data.remote.RegisterRequestDto
import com.application.bibleapp.data.remote.UserResponseDto
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import com.application.bibleapp.R
import com.application.bibleapp.utils.UiText

/**
 * A specific, user-facing failure reason for an auth action, extracted from the backend's
 * ErrorResponse.code (server/docs/api_contract.md) rather than left as a raw HTTP status —
 * so the UI can show "Invalid email or password" instead of "request failed: 401".
 */
sealed class AuthError(val displayMessage: UiText) {
    data object InvalidCredentials : AuthError(UiText.Res(R.string.auth_error_invalid_credentials))
    data object EmailAlreadyRegistered : AuthError(UiText.Res(R.string.auth_error_email_registered))
    // The server's own field messages are English-only; the client validates the same rules
    // first (utils/AuthValidation.kt), so these are rarely seen.
    data class Validation(val fieldErrors: Map<String, String>) :
        AuthError(fieldErrors.values.firstOrNull()?.let { UiText.Raw(it) } ?: UiText.Res(R.string.auth_error_invalid_input))
    data class Unknown(val text: UiText) : AuthError(text)
}

/** Wraps an [AuthError] as a throwable so it can travel through [Result.failure] and be
 *  pattern-matched back out in the UI layer via [AuthException.error]. */
class AuthException(val error: AuthError) : Exception(error.toString())

/**
 * Handles register/login/logout and the current account, talking to the backend's Auth and
 * Account endpoints. Successful register/login save the returned session into [TokenStore];
 * every other authenticated request in the app (HighlightRepository, etc.) then picks that
 * session up automatically via HttpClientProvider's Auth plugin — nothing else in the app
 * needs to touch tokens directly.
 */
class AuthRepository(context: Context) {

    private val tokenStore = TokenStore(context.applicationContext)
    private val client get() = HttpClientProvider.client
    private val baseUrl get() = HttpClientProvider.BASE_URL

    val isLoggedIn: Boolean get() = tokenStore.isLoggedIn
    val currentUserId: String? get() = tokenStore.userId

    suspend fun register(email: String, password: String): Result<Unit> = runCatching {
        val response = client.post("$baseUrl/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequestDto(email, password))
        }
        saveSession(response)
    }

    suspend fun login(email: String, password: String): Result<Unit> = runCatching {
        val response = client.post("$baseUrl/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequestDto(email, password))
        }
        saveSession(response)
    }

    /**
     * Signing out, step one: clears the local session right away and returns its refresh token
     * for [revokeOnServer]. Split in two so the device is signed out without waiting on the
     * network — a user asking to sign out on this device shouldn't be blocked by that.
     */
    fun endLocalSession(): String? {
        val refreshToken = tokenStore.refreshToken
        tokenStore.clear()
        HttpClientProvider.forgetCachedTokens()
        return refreshToken
    }

    /** Signing out, step two: best-effort revocation of [refreshToken] on the server. */
    suspend fun revokeOnServer(refreshToken: String?) {
        if (refreshToken == null) return
        runCatching {
            client.post("$baseUrl/auth/logout") {
                contentType(ContentType.Application.Json)
                setBody(LogoutRequestDto(refreshToken))
            }
        }
    }

    /** Asks the server's health check for nothing but a reply, to wake it if it's asleep (#87). */
    suspend fun pingServer() {
        client.get(HttpClientProvider.HEALTH_URL)
    }

    suspend fun getCurrentUser(): Result<UserResponseDto> = runCatching {
        val response = client.get("$baseUrl/users/me")
        if (!response.status.isSuccess()) throw toAuthException(response)
        response.body()
    }

    suspend fun deleteAccount(password: String): Result<Unit> = runCatching {
        val response = client.delete("$baseUrl/users/me") {
            contentType(ContentType.Application.Json)
            setBody(DeleteAccountRequestDto(password))
        }
        if (!response.status.isSuccess()) throw toAuthException(response)
        tokenStore.clear()
        HttpClientProvider.forgetCachedTokens()
    }

    private suspend fun saveSession(response: HttpResponse) {
        if (!response.status.isSuccess()) throw toAuthException(response)
        val auth = response.body<AuthResponseDto>()
        tokenStore.saveSession(auth.userId, auth.accessToken, auth.refreshToken)
        // The shared client would otherwise keep sending the previous account's token (#86).
        HttpClientProvider.forgetCachedTokens()
    }

    private suspend fun toAuthException(response: HttpResponse): AuthException {
        val error = runCatching { response.body<BackendErrorResponseDto>() }.getOrNull()
        val authError = when (error?.code) {
            "INVALID_CREDENTIALS" -> AuthError.InvalidCredentials
            "EMAIL_ALREADY_REGISTERED" -> AuthError.EmailAlreadyRegistered
            "VALIDATION_ERROR" -> AuthError.Validation(error.fieldErrors ?: emptyMap())
            else -> AuthError.Unknown(
                error?.message?.let { UiText.Raw(it) }
                    ?: UiText.Res(R.string.auth_error_status, response.status.value)
            )
        }
        return AuthException(authError)
    }
}
