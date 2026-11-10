package com.application.bibleapp.data.repository

import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.data.model.DEFAULT_VERSION
import com.application.bibleapp.data.model.DailyVerseRef
import com.application.bibleapp.data.model.DailyVerseUI
import com.application.bibleapp.data.model.VerseOfTheDay
import com.application.bibleapp.data.model.resolveDailyVerseUI

/** A daily verse's text and the translation it was read from. */
data class ResolvedDailyVerse(val verse: DailyVerseUI, val versionId: String)

/**
 * [ref]'s text in [versionId], read from the device only — shared by the daily verse notification
 * and the home screen widget (#245), so both show the same verse the same way.
 *
 * The chosen translation can legitimately be missing it (not downloaded, deleted since, or a
 * partial-canon translation that never covers that book), in which case this would otherwise show
 * nothing at all. KJV is bundled and always has the full canon, so it's the fallback: [ref] there
 * first, then today's curated verse, which always resolves.
 */
suspend fun BibleRepository.resolveDailyVerseText(ref: DailyVerseRef, versionId: String): ResolvedDailyVerse? {
    val kjv = DEFAULT_VERSION.id
    return resolveIn(ref, versionId)?.let { ResolvedDailyVerse(it, versionId) }
        ?: resolveIn(ref, kjv)?.takeIf { versionId != kjv }?.let { ResolvedDailyVerse(it, kjv) }
        ?: resolveIn(VerseOfTheDay.forToday(), kjv)?.let { ResolvedDailyVerse(it, kjv) }
}

/** Today's daily verse reference without touching the network: the one cached for today (by
 *  [DailyVerseFetchWorker][com.application.bibleapp.worker.DailyVerseFetchWorker] or Home), else
 *  today's curated verse. What the widget shows (#245), which never makes network calls. */
suspend fun BibleRepository.todaysDailyVerseOffline(): DailyVerseRef =
    getTodaysCachedDailyVerse() ?: VerseOfTheDay.forToday()

private suspend fun BibleRepository.resolveIn(ref: DailyVerseRef, versionId: String): DailyVerseUI? {
    val bookName = getBookNames(versionId)[ref.bookId]
        ?: BibleBooks.getBookById(ref.bookId)?.name
        ?: return null
    return resolveDailyVerseUI(bookName, ref, getChapter(ref.bookId, ref.chapter, versionId))
}
