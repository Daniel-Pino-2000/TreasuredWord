package com.application.bibleapp.server.models

import kotlinx.serialization.Serializable

/** [code] is the 6-digit code from the verification email (#156). */
@Serializable
data class VerifyEmailRequest(val code: String)

/** Asks for a password reset code for [email] (#157). */
@Serializable
data class PasswordResetSendRequest(val email: String)

/** Sets [newPassword] on the account with [email], if [code] is its newest reset code (#157). */
@Serializable
data class PasswordResetRequest(val email: String, val code: String, val newPassword: String)
