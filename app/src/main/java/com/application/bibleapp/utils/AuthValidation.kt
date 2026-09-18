package com.application.bibleapp.utils

/** Mirrors the backend's own check (server/routes/AuthRoutes.kt EMAIL_REGEX) so a malformed
 *  email is caught before a round trip, not just after the server rejects it. */
private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

fun isValidEmailFormat(email: String): Boolean = EMAIL_REGEX.matches(email)

/** Mirrors the backend's own minimum (server/routes/AuthRoutes.kt MIN_PASSWORD_LENGTH). */
const val MIN_PASSWORD_LENGTH = 8
