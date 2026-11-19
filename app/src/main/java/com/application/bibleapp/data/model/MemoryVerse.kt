package com.application.bibleapp.data.model

import com.application.bibleapp.data.remote.MemoryProgressDto
import com.application.bibleapp.data.remote.MemoryVerseResponseDto
import com.application.bibleapp.data.remote.VerseLocationDto
import com.application.bibleapp.utils.parseIsoTimestamp

/** Where a passage on the Memorize list stands (#362). Moved along by the practice games (#363). */
enum class MemoryStatus {
    NEW,
    LEARNING,
    MEMORIZED;

    companion object {
        fun fromStored(value: String?): MemoryStatus = entries.firstOrNull { it.name == value } ?: NEW
    }
}

/** The highest practice level; a passage at it that passes the typing game is Memorized (#363). */
const val MAX_MEMORY_LEVEL = 5

/** The longest passage the Memorize list takes, so the games stay playable (#362). */
const val MAX_MEMORY_PASSAGE_VERSES = 6

/**
 * A passage on the Memorize list (#362): verses [verseStart]..[verseEnd] of one chapter, in the
 * version it was read in, with its practice progress. Kept apart from highlights, which mean
 * "this stood out to me". Stored and synced like a highlight (see BibleDatabaseManager's
 * `memory_verses`): [localId] is this device's row, [remoteId] the server's id once pushed.
 * [progressAt] is when the progress last changed on a device, so an older device's progress
 * never replaces newer progress (docs/api_contract.md decision 21).
 */
data class MemoryVerse(
    val localId: Long,
    val remoteId: String?,
    val versionId: String,
    val bookId: Int,
    val chapter: Int,
    val verseStart: Int,
    val verseEnd: Int,
    val status: MemoryStatus = MemoryStatus.NEW,
    val level: Int = 0,
    val nextReviewAt: String? = null,
    val lastPracticedAt: String? = null,
    val correctStreak: Int = 0,
    val memorizedAt: String? = null,
    val progressAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING
) {
    /** Every verse of the passage, in order, for the shared verse text and reference helpers. */
    val verses: List<VerseLocationDto>
        get() = (verseStart..verseEnd).map { VerseLocationDto(bookId, chapter, it) }

    val progress: MemoryProgressDto
        get() = MemoryProgressDto(status.name, level, nextReviewAt, lastPracticedAt, correctStreak, memorizedAt, progressAt)

    fun isSamePassage(other: MemoryPassage): Boolean =
        bookId == other.bookId && chapter == other.chapter && verseStart == other.verseStart && verseEnd == other.verseEnd
}

/** Verses [verseStart]..[verseEnd] of one chapter, as picked in the reader. */
data class MemoryPassage(val bookId: Int, val chapter: Int, val verseStart: Int, val verseEnd: Int)

/** What the reader's selection makes as a Memorize passage (#362). */
sealed interface MemorySelection {
    data class Passage(val passage: MemoryPassage) : MemorySelection
    /** More than [MAX_MEMORY_PASSAGE_VERSES] verses. */
    data object TooLong : MemorySelection
    /** Verses with a gap between them, or from different chapters. */
    data object NotOnePassage : MemorySelection
    data object Empty : MemorySelection
}

/** One verse, or a run of consecutive verses in one chapter, up to [MAX_MEMORY_PASSAGE_VERSES]. */
fun memorySelectionOf(selection: Collection<VerseLocationDto>): MemorySelection {
    val verses = selection.distinct()
    if (verses.isEmpty()) return MemorySelection.Empty
    val first = verses.first()
    if (verses.any { it.bookId != first.bookId || it.chapter != first.chapter }) return MemorySelection.NotOnePassage
    val numbers = verses.map { it.verse }.sorted()
    if (numbers.last() - numbers.first() + 1 != numbers.size) return MemorySelection.NotOnePassage
    if (numbers.size > MAX_MEMORY_PASSAGE_VERSES) return MemorySelection.TooLong
    return MemorySelection.Passage(MemoryPassage(first.bookId, first.chapter, numbers.first(), numbers.last()))
}

/** How the Memorize tab orders its cards (#362). */
enum class MemorySort { NEWEST, DUE }

/**
 * [NEWEST]: most recently added first. [DUE]: the passage that most needs practice first —
 * never-practiced ones (due as soon as they're added, oldest first), then by next review date;
 * Memorized ones only come back about monthly (#363), so they fall to the end on their own.
 */
fun List<MemoryVerse>.sortedFor(sort: MemorySort): List<MemoryVerse> = when (sort) {
    MemorySort.NEWEST -> sortedByDescending { parseIsoTimestamp(it.createdAt) ?: 0L }
    MemorySort.DUE -> sortedWith(
        compareBy<MemoryVerse> { parseIsoTimestamp(it.nextReviewAt ?: it.createdAt) ?: 0L }
            .thenBy { parseIsoTimestamp(it.createdAt) ?: 0L }
    )
}

/** True if this device's copy already says everything [dto] does, so a pull changes nothing. */
fun MemoryVerse.matchesServer(dto: MemoryVerseResponseDto): Boolean =
    remoteId == dto.id && versionId == dto.versionId && bookId == dto.bookId && chapter == dto.chapter &&
        verseStart == dto.verseStart && verseEnd == dto.verseEnd && progress == dto.progress &&
        createdAt == dto.createdAt && updatedAt == dto.updatedAt && deletedAt == null
