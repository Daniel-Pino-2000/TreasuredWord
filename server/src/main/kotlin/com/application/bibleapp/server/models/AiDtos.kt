package com.application.bibleapp.server.models

import kotlinx.serialization.Serializable

/** One message of the conversation the app sends. `role` is "user" or "assistant", never "system". */
@Serializable
data class AiMessageDto(val role: String, val content: String)

/** `POST /api/v1/ai/ask` (#124). `language` is one of the app's tags, e.g. "es" or "pt-BR". */
@Serializable
data class AiAskRequest(val messages: List<AiMessageDto>, val language: String)

/**
 * `POST /api/v1/ai/reports` (#125). `reason` is WRONG_TEACHING, WRONG_VERSE, HARMFUL or OTHER; a
 * String here so an unknown one is a VALIDATION_ERROR rather than an unreadable body.
 */
@Serializable
data class AiReportRequest(
    val question: String,
    val answer: String,
    val reason: String,
    val comment: String? = null,
    val language: String,
    val promptVersion: String? = null
)

/** `remainingToday` is how many of the day's questions are left after this one. */
@Serializable
data class AiAskResponse(
    val answer: String,
    val crisis: Boolean,
    val remainingToday: Int,
    val promptVersion: String
)
