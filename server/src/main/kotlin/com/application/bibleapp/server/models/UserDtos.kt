package com.application.bibleapp.server.models

import kotlinx.serialization.Serializable

/** [hasPassword] and [hasGoogle] say how the account can sign in, so the app knows how to
 *  confirm deleting it (#112). */
@Serializable
data class UserResponse(
    val id: String,
    val email: String,
    val createdAt: String,
    val hasPassword: Boolean,
    val hasGoogle: Boolean
)

/** Exactly one of the two: the account's [password], or a fresh [googleIdToken] for an account
 *  that uses Google sign-in (#112). */
@Serializable
data class DeleteAccountRequest(val password: String? = null, val googleIdToken: String? = null)
