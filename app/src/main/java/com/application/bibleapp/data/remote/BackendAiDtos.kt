package com.application.bibleapp.data.remote

import kotlinx.serialization.Serializable

/**
 * Client-side mirror of the Bible Helper's endpoints (#127). Built against the draft contract in
 * docs/ai_helper_plan.md section 5 until #124 copies it into docs/api_contract.md; keep the two
 * in step.
 */

/** `role` is "user" or "assistant" ([com.application.bibleapp.data.model.AiRole.stored]). */
@Serializable
data class AiMessageDto(val role: String, val content: String)

/** `messages`: 1–10, oldest first, alternating, the last one from the user. `language` is one of
 *  [com.application.bibleapp.utils.AppLanguage.supportedTags]. */
@Serializable
data class AiAskRequestDto(val messages: List<AiMessageDto>, val language: String)

/** The answer marks the verses it cites as `[[JHN 3:16]]` (#129). `remainingToday` is how many of
 *  the day's questions are left after this one. */
@Serializable
data class AiAskResponseDto(
    val answer: String,
    val crisis: Boolean = false,
    val remainingToday: Int? = null,
    val promptVersion: String? = null
)

/** Why an answer was reported (#130). */
@Serializable
enum class AiReportReason { WRONG_TEACHING, WRONG_VERSE, HARMFUL, OTHER }

/** `comment` is optional, at most 500 characters. The server stores reports without the user's ID. */
@Serializable
data class AiReportRequestDto(
    val question: String,
    val answer: String,
    val reason: AiReportReason,
    val comment: String? = null,
    val language: String,
    val promptVersion: String? = null
)

/** The shared error body (docs/api_contract.md's `ErrorResponse`). Everything is optional here:
 *  Ktor's own rate limiter answers 429 with no body at all. */
@Serializable
data class BackendErrorDto(
    val code: String? = null,
    val message: String? = null,
    val fieldErrors: Map<String, String>? = null
)
