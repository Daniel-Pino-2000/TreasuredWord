package com.application.bibleapp.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Client-side mirror of the backend's sync contract (Highlights, Notes, Reading Progress) —
 * see server/docs/api_contract.md. A highlight/note covers a *list* of verses, not a
 * contiguous range, so a user can select several verses (contiguous or not, even across
 * chapters) and save them as one item.
 */

@Serializable
data class VerseLocationDto(val bookId: Int, val chapter: Int, val verse: Int)

private val verseLocationsJson = Json { ignoreUnknownKeys = true }
private val verseLocationListSerializer = ListSerializer(VerseLocationDto.serializer())

/** Used to store a highlight/note's verse list as one JSON column locally (see BibleDatabaseManager). */
fun List<VerseLocationDto>.encodeToJson(): String =
    verseLocationsJson.encodeToString(verseLocationListSerializer, this)

/** Empty on malformed/missing JSON rather than throwing — a locally corrupted row shouldn't crash the reader. */
fun decodeVerseLocationsOrEmpty(json: String?): List<VerseLocationDto> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        verseLocationsJson.decodeFromString(verseLocationListSerializer, json)
    } catch (e: Exception) {
        emptyList()
    }
}

@Serializable
data class CreateHighlightRequestDto(
    val versionId: String,
    val verses: List<VerseLocationDto>,
    val color: Int
)

/** Color is the only editable field after creation — delete and recreate to change verses. */
@Serializable
data class UpdateHighlightRequestDto(val color: Int)

@Serializable
data class HighlightResponseDto(
    val id: String,
    val versionId: String,
    val verses: List<VerseLocationDto>,
    val color: Int,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null
)

@Serializable
data class HighlightListResponseDto(val highlights: List<HighlightResponseDto>)

@Serializable
data class CreateNoteRequestDto(
    val versionId: String,
    val verses: List<VerseLocationDto>,
    val text: String
)

@Serializable
data class UpdateNoteRequestDto(val verses: List<VerseLocationDto>, val text: String)

@Serializable
data class NoteResponseDto(
    val id: String,
    val versionId: String,
    val verses: List<VerseLocationDto>,
    val text: String,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null
)

@Serializable
data class NoteListResponseDto(val notes: List<NoteResponseDto>)

@Serializable
data class ReadingProgressResponseDto(
    val versionId: String,
    val bookId: Int,
    val chapter: Int,
    val verse: Int,
    val updatedAt: String
)

@Serializable
data class UpdateReadingProgressRequestDto(
    val versionId: String,
    val bookId: Int,
    val chapter: Int,
    val verse: Int,
    /** This device's local clock time for this read event — see api_contract.md decision 8:
     *  the server ignores the write if it's older than what's already stored, so a device
     *  that was offline for a while can't clobber a newer position from another device. */
    val readAt: String
)
