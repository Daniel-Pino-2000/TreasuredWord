package com.application.bibleapp.server.models

import kotlinx.serialization.Serializable

@Serializable
data class CreateHighlightRequest(
    val versionId: String,
    val verses: List<VerseLocation>,
    val color: Int
)

/** Color is the only editable field after creation - see docs/api_contract.md decision 14. */
@Serializable
data class UpdateHighlightRequest(val color: Int)

@Serializable
data class HighlightResponse(
    val id: String,
    val versionId: String,
    val verses: List<VerseLocation>,
    val color: Int,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null
)

/** [serverTime] is the server's clock when the list was read, for the client to send back as the
 *  next `updatedSince` instead of its own clock, which may be ahead (#84). */
@Serializable
data class HighlightListResponse(val highlights: List<HighlightResponse>, val serverTime: String)
