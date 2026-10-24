package com.application.bibleapp.server.models

import kotlinx.serialization.Serializable

@Serializable
data class CreateNoteRequest(
    val versionId: String,
    val verses: List<VerseLocation>,
    val text: String
)

/** Unlike a highlight, both the text and the verse list can be edited in place - decision 14. */
@Serializable
data class UpdateNoteRequest(
    val verses: List<VerseLocation>,
    val text: String
)

@Serializable
data class NoteResponse(
    val id: String,
    val versionId: String,
    val verses: List<VerseLocation>,
    val text: String,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null
)

/** See [HighlightListResponse.serverTime] (#84). */
@Serializable
data class NoteListResponse(val notes: List<NoteResponse>, val serverTime: String)
