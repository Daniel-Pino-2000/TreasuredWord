package com.application.bibleapp.server.models

import kotlinx.serialization.Serializable

/** One message of the conversation the app sends. `role` is "user" or "assistant", never "system". */
@Serializable
data class AiMessageDto(val role: String, val content: String)

/**
 * The passage "Ask about this verse" attaches (#132): a USFM reference such as "JHN 3:16-18" and
 * its text in the reader's own translation, at most 2,000 characters.
 */
@Serializable
data class AiPassage(val reference: String, val text: String)

/** `POST /api/v1/ai/ask` (#124). `language` is one of the app's tags, e.g. "es" or "pt-BR".
 *  [passage] is only there when the question was asked from the reader (#132). [starter] names
 *  one of the Ask tab's starter questions (#284), for a new conversation's first question: its
 *  answer comes ready and doesn't use a daily question. */
@Serializable
data class AiAskRequest(
    val messages: List<AiMessageDto>,
    val language: String,
    val passage: AiPassage? = null,
    val starter: String? = null
)

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

/** A `text` event of `POST /api/v1/ai/ask/stream` (#285): the next piece of the answer. */
@Serializable
data class AiStreamText(val text: String)
