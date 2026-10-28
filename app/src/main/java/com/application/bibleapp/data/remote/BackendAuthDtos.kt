package com.application.bibleapp.data.remote

import kotlinx.serialization.Serializable

/**
 * Client-side mirror of the backend's Auth/Account contract — see
 * server/docs/api_contract.md (Auth and Account sections). Field names and shapes must match
 * the server's models/AuthDtos.kt and models/UserDtos.kt exactly; there's no schema validation
 * across the network boundary, so a drift here silently breaks deserialization instead of
 * failing loudly.
 */

@Serializable
data class RegisterRequestDto(val email: String, val password: String)

@Serializable
data class LoginRequestDto(val email: String, val password: String)

@Serializable
data class GoogleSignInRequestDto(val idToken: String)

@Serializable
data class RefreshRequestDto(val refreshToken: String)

@Serializable
data class LogoutRequestDto(val refreshToken: String)

@Serializable
data class AuthResponseDto(
    val userId: String,
    val accessToken: String,
    val accessTokenExpiresInSeconds: Long,
    val refreshToken: String
)

/** [hasPassword]/[hasGoogle] default to "unknown" for a server from before Google sign-in (#112). */
@Serializable
data class UserResponseDto(
    val id: String,
    val email: String,
    val createdAt: String,
    val hasPassword: Boolean? = null,
    val hasGoogle: Boolean? = null
)

/** One of the two: the password, or a fresh Google ID token (#112). */
@Serializable
data class DeleteAccountRequestDto(val password: String? = null, val googleIdToken: String? = null)

/** Matches every non-2xx response shape from the backend (server/models/ErrorResponse.kt). */
@Serializable
data class BackendErrorResponseDto(
    val code: String,
    val message: String,
    val fieldErrors: Map<String, String>? = null
)
