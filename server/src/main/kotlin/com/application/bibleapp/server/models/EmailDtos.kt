package com.application.bibleapp.server.models

import kotlinx.serialization.Serializable

/** [code] is the 6-digit code from the verification email (#156). */
@Serializable
data class VerifyEmailRequest(val code: String)
