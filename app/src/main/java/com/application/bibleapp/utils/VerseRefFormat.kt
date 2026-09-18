package com.application.bibleapp.utils

import com.application.bibleapp.data.remote.VerseLocationDto

/**
 * Human-readable reference for a highlight/note's verse list, e.g. "Juan 3:16", a contiguous
 * "Juan 3:16-18", or "Juan 3:16 +2 more" for a scattered selection — verse order in storage
 * isn't guaranteed (server/docs/api_contract.md decision 12), so this always sorts by
 * (bookId, chapter, verse) first, matching Bible order rather than insertion order.
 */
fun formatVerseRefs(verses: List<VerseLocationDto>, bookName: (Int) -> String): String {
    if (verses.isEmpty()) return ""
    val sorted = verses.sortedWith(compareBy({ it.bookId }, { it.chapter }, { it.verse }))
    val first = sorted.first()
    val ref = "${bookName(first.bookId)} ${first.chapter}:${first.verse}"
    if (sorted.size == 1) return ref

    val isContiguousRun = sorted.zipWithNext().all { (a, b) ->
        a.bookId == b.bookId && a.chapter == b.chapter && b.verse == a.verse + 1
    }
    return if (isContiguousRun) {
        "$ref-${sorted.last().verse}"
    } else {
        "$ref +${sorted.size - 1} more"
    }
}
