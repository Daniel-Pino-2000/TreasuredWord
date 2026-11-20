package com.application.bibleapp.widget

import com.application.bibleapp.data.model.MemoryStatus
import com.application.bibleapp.data.model.MemoryVerse
import com.application.bibleapp.utils.parseIsoTimestamp
import kotlin.random.Random

/*
 * Which passage the "Verse to memorize" widget (#364) shows. Kept free of Android so the rules
 * are unit-tested: one passage a day, picked at random, preferring what most needs practice.
 */

/** What a widget showed, and on which day ([dailyVerseDateKey][com.application.bibleapp.data.repository.dailyVerseDateKey]).
 *  [previousLocalId] is the day before's, kept so a re-pick later the same day still avoids it. */
internal data class MemorizePick(val day: String, val localId: Long, val previousLocalId: Long?)

/**
 * Today's passage for one widget. The same all day: [stored] is kept while it's from [today] and
 * still on the list, even if practice since then changed what's due. Otherwise a new pick, which
 * avoids yesterday's passage when the list has another. Null for an empty list.
 *
 * The pick is random but seeded by the day and [widgetId], so it doesn't change on a redraw and
 * two widgets usually show different passages.
 */
internal fun pickMemorizeVerse(
    verses: List<MemoryVerse>,
    today: String,
    widgetId: Int,
    stored: MemorizePick?,
    nowMillis: Long
): MemorizePick? {
    if (verses.isEmpty()) return null
    if (stored != null && stored.day == today && verses.any { it.localId == stored.localId }) return stored
    // A new day: avoid the last one shown. The same day (its passage was removed): still avoid
    // the one before it, as the first pick today did.
    val previous = if (stored?.day == today) stored.previousLocalId else stored?.localId
    val candidates = verses.filter { it.localId != previous }.ifEmpty { verses }
    val tier = candidates.groupBy { memorizeTier(it, nowMillis) }.minBy { it.key }.value
    val random = Random(seedFor(today, widgetId))
    val picked = tier.sortedBy { it.localId }[random.nextInt(tier.size)]
    return MemorizePick(today, picked.localId, previous)
}

/**
 * 0: due for review (#363's schedule) — a passage never practiced is due as soon as it's added,
 * as on the Memorize tab's "Due" sort; 1: still being learned; 2: Memorized and not due yet.
 */
internal fun memorizeTier(verse: MemoryVerse, nowMillis: Long): Int {
    val nextReview = verse.nextReviewAt?.let(::parseIsoTimestamp)
    val due = if (nextReview != null) nextReview <= nowMillis else verse.status != MemoryStatus.MEMORIZED
    return when {
        due -> 0
        verse.status != MemoryStatus.MEMORIZED -> 1
        else -> 2
    }
}

private fun seedFor(day: String, widgetId: Int): Long = day.hashCode().toLong() * 31 + widgetId

/**
 * The widget's hint mode (#364): each word cut to its first letter, keeping the punctuation
 * around it and any verse number, so "For God so loved the world," reads "F G s l t w,". A word
 * is whatever sits between spaces, so in a language written without spaces (Chinese) each
 * clause keeps its first character.
 */
internal fun firstLettersHint(text: String): String =
    text.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { word ->
        val first = word.indexOfFirst { it.isLetterOrDigit() }
        if (first < 0) return@joinToString word // a verse number (superscript) or a dash
        val trailing = word.takeLastWhile { !it.isLetterOrDigit() }
        word.substring(0, first + 1) + trailing
    }
