package com.application.bibleapp.server.models

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(val email: String, val password: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

/** [idToken] is the Google ID token the app got from Credential Manager (#112). */
@Serializable
data class GoogleSignInRequest(val idToken: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String)

@Serializable
data class AuthResponse(
    val userId: String,
    val accessToken: String,
    val accessTokenExpiresInSeconds: Long,
    val refreshToken: String
)
