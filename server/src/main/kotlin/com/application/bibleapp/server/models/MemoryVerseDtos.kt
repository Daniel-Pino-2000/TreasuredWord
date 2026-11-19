package com.application.bibleapp.server.models

import kotlinx.serialization.Serializable

/**
 * A passage's practice progress (#362), moved along by the practice games (#363). [status] is
 * "NEW", "LEARNING" or "MEMORIZED"; [level] 0..5. Timestamps are ISO-8601. [progressAt] is when
 * this progress was reached on the device: the server keeps whichever progress is newer, so an
 * older device can't move it backwards (decision 21).
 */
@Serializable
data class MemoryProgress(
    val status: String = "NEW",
    val level: Int = 0,
    val nextReviewAt: String? = null,
    val lastPracticedAt: String? = null,
    val correctStreak: Int = 0,
    val memorizedAt: String? = null,
    val progressAt: String? = null
)

/** The progress travels with the create, so a passage practiced before its first sync keeps it. */
@Serializable
data class CreateMemoryVerseRequest(
    val versionId: String,
    val bookId: Int,
    val chapter: Int,
    val verseStart: Int,
    val verseEnd: Int,
    val progress: MemoryProgress = MemoryProgress()
)

/** Progress is the only editable part; the passage itself is fixed (delete and add again). */
@Serializable
data class UpdateMemoryVerseRequest(val progress: MemoryProgress)

@Serializable
data class MemoryVerseResponse(
    val id: String,
    val versionId: String,
    val bookId: Int,
    val chapter: Int,
    val verseStart: Int,
    val verseEnd: Int,
    val progress: MemoryProgress,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null
)

/** See [HighlightListResponse.serverTime] (#84). */
@Serializable
data class MemoryVerseListResponse(val memoryVerses: List<MemoryVerseResponse>, val serverTime: String)
