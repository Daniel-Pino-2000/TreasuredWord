package com.application.bibleapp.data.model

import com.application.bibleapp.data.remote.MemoryProgressDto
import com.application.bibleapp.utils.formatIsoTimestamp
import com.application.bibleapp.utils.parseIsoTimestamp
import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random

/*
 * The Memorize practice games (#363): which game a passage gets, the review schedule, and the
 * daily streak. Kept free of Android so the rules are unit-tested (MemoryPracticeTest).
 */

/**
 * The practice games, easiest first. [level] is the passage level a game belongs to, which
 * Mixed mode picks by; [WHICH_REFERENCE] is a bonus round with no level of its own.
 */
enum class PracticeGame(val level: Int) {
    READ_AND_LISTEN(0),
    FILL_THE_GAPS(1),
    PUT_IN_ORDER(2),
    FIRST_LETTERS(3),
    FADING_WORDS(4),
    TYPE_FROM_MEMORY(5),
    WHICH_REFERENCE(-1);

    companion object {
        fun fromStored(value: String?): PracticeGame? = entries.firstOrNull { it.name == value }
    }
}

/** What a practice runs: [game] for every passage, or null for Mixed (each passage's own level). */
data class PracticeChoice(val game: PracticeGame?)

/**
 * The games a passage's page swipes through sideways, easiest first (#416): every game with a
 * level. "Which reference?" isn't one of them; it gets a page of its own.
 */
val PRACTICE_LADDER: List<PracticeGame> = PracticeGame.entries.filter { it.level >= 0 }

/**
 * Where a passed game leads (#416). In Mixed, the passage's next game, so a passage climbs the
 * ladder in one go; null means the next passage instead: the top of the ladder, a bonus round, or
 * a practice of one game for every passage, where that game was picked on purpose.
 */
fun nextGameAfterPass(game: PracticeGame, mixed: Boolean): PracticeGame? =
    if (!mixed) null else PRACTICE_LADDER.getOrNull(PRACTICE_LADDER.indexOf(game) + 1)?.takeIf { game in PRACTICE_LADDER }

/**
 * Whether a pass moves on by itself after a moment (#416). Not after "Type it from memory": its
 * result marks the words that were missed, and that's worth reading before going on.
 */
fun movesOnAfterPass(game: PracticeGame): Boolean = game != PracticeGame.TYPE_FROM_MEMORY

/** The game for a passage's level; a Memorized passage keeps proving itself by typing it. */
fun gameForProgress(verse: MemoryVerse): PracticeGame =
    if (verse.status == MemoryStatus.MEMORIZED) PracticeGame.TYPE_FROM_MEMORY
    else PracticeGame.entries.first { it.level == verse.level.coerceIn(0, MAX_MEMORY_LEVEL) }

/**
 * Days until the next review after passing at each level (0..5): 1, 2, 4, 7, 14, then 30, the
 * Leitner schedule the developer accepted for #363. A Memorized passage comes back after 30 too.
 */
val REVIEW_INTERVAL_DAYS = listOf(1, 2, 4, 7, 14, 30)

/** A failed review comes back the next day. */
const val RETRY_INTERVAL_DAYS = 1

/** Passes of "Type it from memory" at the top level, on different days, that make a passage Memorized. */
const val PASSES_TO_MEMORIZE = 2

/**
 * Whether [verse] is due for practice today: its review date is today or earlier, or it has
 * never been practiced (a Memorized one with no date can't happen, and isn't due). Compared by
 * day, so a review set for "tomorrow" is due all of tomorrow, whatever time it was practiced.
 */
fun isDueToday(verse: MemoryVerse, nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Boolean {
    val next = verse.nextReviewAt?.let(::parseIsoTimestamp) ?: return verse.status != MemoryStatus.MEMORIZED
    return next < startOfDay(nowMillis, 1, timeZone)
}

/** What one finished game did to a passage. [progress] is null when nothing changed. */
data class PracticeOutcome(
    val passed: Boolean,
    /** Whether the result moved the schedule: only a due passage, played at its level or above, does. */
    val counted: Boolean,
    val levelBefore: Int,
    val levelAfter: Int,
    val newlyMemorized: Boolean,
    val progress: MemoryProgressDto?
) {
    val movedUp: Boolean get() = counted && passed && (levelAfter > levelBefore || newlyMemorized)
}

/**
 * The schedule (#363). Only a passage that's due counts, so practicing early or "again" can't
 * skip the spacing; the rest is extra practice. A game easier than the passage's level never
 * counts, and one harder than it can move the passage up but never down.
 *
 * - Pass: up one level (to at most [MAX_MEMORY_LEVEL]), next review after [REVIEW_INTERVAL_DAYS].
 * - Fail: down one level, back tomorrow. A Memorized passage that fails is Learning again, at 4.
 * - Memorized: "Type it from memory" passed at the top level on [PASSES_TO_MEMORIZE] different
 *   days. [MemoryVerse.correctStreak] counts those passes: it starts again at 0 whenever the level
 *   changes, so it's the passes at the current level.
 */
fun applyPracticeResult(
    verse: MemoryVerse,
    game: PracticeGame,
    passed: Boolean,
    nowMillis: Long,
    timeZone: TimeZone = TimeZone.getDefault()
): PracticeOutcome {
    val levelGame = gameForProgress(verse)
    val level = verse.level.coerceIn(0, MAX_MEMORY_LEVEL)
    val counts = game != PracticeGame.WHICH_REFERENCE && isDueToday(verse, nowMillis, timeZone) &&
        (game == levelGame || (passed && game.level > levelGame.level))
    if (!counts) return PracticeOutcome(passed, counted = false, level, level, newlyMemorized = false, progress = null)

    val now = formatIsoTimestamp(nowMillis)
    val wasMemorized = verse.status == MemoryStatus.MEMORIZED
    val progress = if (passed) {
        val newLevel = (level + 1).coerceAtMost(MAX_MEMORY_LEVEL)
        val atTop = level == MAX_MEMORY_LEVEL && game == PracticeGame.TYPE_FROM_MEMORY
        // Another day's pass at the top: the same day's can't happen (a pass sets the next
        // review weeks away), but a clock change could, so it's checked.
        val newDay = verse.lastPracticedAt?.let(::parseIsoTimestamp)
            ?.let { startOfDay(it, 0, timeZone) != startOfDay(nowMillis, 0, timeZone) } ?: true
        val streak = when {
            newLevel != level -> 0
            atTop && newDay -> verse.correctStreak + 1
            else -> verse.correctStreak
        }
        val memorized = wasMemorized || (atTop && streak >= PASSES_TO_MEMORIZE)
        MemoryProgressDto(
            status = (if (memorized) MemoryStatus.MEMORIZED else MemoryStatus.LEARNING).name,
            level = newLevel,
            nextReviewAt = formatIsoTimestamp(startOfDay(nowMillis, REVIEW_INTERVAL_DAYS[level], timeZone)),
            lastPracticedAt = now,
            correctStreak = streak,
            memorizedAt = if (memorized) verse.memorizedAt ?: now else null,
            progressAt = now
        )
    } else {
        val newLevel = (if (wasMemorized) MAX_MEMORY_LEVEL else level) - 1
        MemoryProgressDto(
            status = MemoryStatus.LEARNING.name,
            level = newLevel.coerceAtLeast(0),
            nextReviewAt = formatIsoTimestamp(startOfDay(nowMillis, RETRY_INTERVAL_DAYS, timeZone)),
            lastPracticedAt = now,
            correctStreak = 0,
            memorizedAt = null,
            progressAt = now
        )
    }
    return PracticeOutcome(
        passed = passed,
        counted = true,
        levelBefore = level,
        levelAfter = progress.level,
        newlyMemorized = !wasMemorized && progress.status == MemoryStatus.MEMORIZED.name,
        progress = progress
    )
}

/** [verse] with [progress] applied, as the database now has it. */
fun MemoryVerse.withProgress(progress: MemoryProgressDto): MemoryVerse = copy(
    status = MemoryStatus.fromStored(progress.status),
    level = progress.level,
    nextReviewAt = progress.nextReviewAt,
    lastPracticedAt = progress.lastPracticedAt,
    correctStreak = progress.correctStreak,
    memorizedAt = progress.memorizedAt,
    progressAt = progress.progressAt
)

/** Local midnight [plusDays] days after the day [millis] falls on. */
internal fun startOfDay(millis: Long, plusDays: Int, timeZone: TimeZone): Long =
    Calendar.getInstance(timeZone).apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, plusDays)
    }.timeInMillis

/** One page of the practice feed: a passage and its game, or the summary at the end. */
sealed interface PracticePage {
    data class Game(val verseLocalId: Long, val game: PracticeGame, val bonus: Boolean = false) : PracticePage
    data object Summary : PracticePage
}

/**
 * The feed for [verses] (already in the order to practice them). Mixed gives each passage its
 * level's game and adds one "Which reference?" bonus round at the end, on a passage already past
 * the first level when there is one, if [canAskReference] says there are enough choices for it.
 */
fun buildPracticePages(
    verses: List<MemoryVerse>,
    choice: PracticeChoice,
    random: Random,
    canAskReference: (MemoryVerse) -> Boolean
): List<PracticePage> {
    val pages = mutableListOf<PracticePage>()
    verses.forEach { verse -> pages += PracticePage.Game(verse.localId, choice.game ?: gameForProgress(verse)) }
    if (choice.game == null && verses.size >= 2) {
        val candidates = verses.filter(canAskReference)
        val bonus = candidates.filter { it.level > 0 || it.status == MemoryStatus.MEMORIZED }.ifEmpty { candidates }
        if (bonus.isNotEmpty()) {
            pages += PracticePage.Game(bonus[random.nextInt(bonus.size)].localId, PracticeGame.WHICH_REFERENCE, bonus = true)
        }
    }
    if (pages.isNotEmpty()) pages += PracticePage.Summary
    return pages
}

/**
 * The daily streak (#363): days in a row with at least one game played. [lastDay] and [today]/
 * [yesterday] are day keys ([com.application.bibleapp.data.repository.dailyVerseDateKey]).
 */
data class PracticeStreak(val lastDay: String?, val days: Int) {
    /** The streak as of [today]: still running if the last practice was today or yesterday. */
    fun current(today: String, yesterday: String): Int = if (lastDay == today || lastDay == yesterday) days else 0

    /** After practicing on [today]. */
    fun practiced(today: String, yesterday: String): PracticeStreak = when (lastDay) {
        today -> this
        yesterday -> PracticeStreak(today, days + 1)
        else -> PracticeStreak(today, 1)
    }
}

/** "Which reference?" asks one of two ways round. */
enum class ReferenceQuestion { PICK_REFERENCE, PICK_TEXT }

/** How many wrong choices "Which reference?" shows next to the right one. */
const val REFERENCE_DECOYS = 3

/**
 * The wrong choices for "Which reference?" on [target]: other passages on the Memorize list
 * first, then passages of the same length elsewhere in its chapter (not overlapping it, so their
 * text differs), up to [REFERENCE_DECOYS]. [usable] says whether a passage can be shown (its text
 * is on the device). [chapterVerses] is how many verses the target's chapter has. Fewer than
 * [REFERENCE_DECOYS] means the round can't be played.
 */
fun referenceDecoys(
    target: MemoryPassage,
    others: List<MemoryPassage>,
    chapterVerses: Int,
    random: Random,
    usable: (MemoryPassage) -> Boolean
): List<MemoryPassage> {
    val fromList = others
        .filter { it != target && usable(it) }
        .distinct()
        .shuffled(random)
    val length = target.verseEnd - target.verseStart + 1
    val nearby = (1..(chapterVerses - length + 1))
        .map { start -> MemoryPassage(target.bookId, target.chapter, start, start + length - 1) }
        .filter { it.verseEnd < target.verseStart || it.verseStart > target.verseEnd }
        .filter { it !in fromList && usable(it) }
        .shuffled(random)
    return (fromList + nearby).take(REFERENCE_DECOYS)
}

val MemoryVerse.passage: MemoryPassage get() = MemoryPassage(bookId, chapter, verseStart, verseEnd)
