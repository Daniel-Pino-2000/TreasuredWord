package com.application.bibleapp.utils

import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.data.remote.VerseLocationDto
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.application.bibleapp.R

/**
 * Human-readable reference for a highlight/note's verse list, e.g. "Juan 3:16", a contiguous
 * "Juan 3:16-18", or "Juan 3:16 +2 more" for a scattered selection — verse order in storage
 * isn't guaranteed (server/docs/api_contract.md decision 12), so this always sorts by
 * (bookId, chapter, verse) first, matching Bible order rather than insertion order.
 * [withMore] adds the app-language "+2 more" to a reference, given how many verses it leaves out.
 */
fun formatVerseRefs(
    verses: List<VerseLocationDto>,
    bookName: (Int) -> String,
    withMore: (ref: String, moreCount: Int) -> String
): String {
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
        withMore(ref, sorted.size - 1)
    }
}

/** [formatVerseRefs] with the app language's "+2 more". */
@Composable
fun verseRefsText(verses: List<VerseLocationDto>, bookName: (Int) -> String): String {
    LocalConfiguration.current // recompose on a language change, like stringResource
    val resources = LocalContext.current.resources
    return formatVerseRefs(verses, bookName) { ref, moreCount ->
        resources.getQuantityString(R.plurals.verse_ref_with_more, moreCount, ref, moreCount)
    }
}

/**
 * A complete reference naming every verse — unlike [formatVerseRefs]'s compact "+2 more", for
 * text that leaves the app (copy/share) and so has to stand on its own: consecutive verses
 * collapse into ranges, a chapter change restarts with "chapter:", a book change with the
 * book's name — e.g. "Lucas 12:4-6, 9; 13:1".
 */
fun formatFullVerseRefs(verses: List<VerseLocationDto>, bookName: (Int) -> String): String {
    if (verses.isEmpty()) return ""
    val sorted = verses.distinct().sortedWith(compareBy({ it.bookId }, { it.chapter }, { it.verse }))

    // Runs of consecutive verses within one book+chapter.
    val runs = mutableListOf<MutableList<VerseLocationDto>>()
    sorted.forEach { location ->
        val run = runs.lastOrNull()
        val last = run?.last()
        if (last != null && last.bookId == location.bookId && last.chapter == location.chapter &&
            location.verse == last.verse + 1
        ) {
            run.add(location)
        } else {
            runs.add(mutableListOf(location))
        }
    }

    return buildString {
        var previous: VerseLocationDto? = null
        runs.forEach { run ->
            val first = run.first()
            val span = if (run.size > 1) "${first.verse}-${run.last().verse}" else "${first.verse}"
            when {
                previous == null -> append("${bookName(first.bookId)} ${first.chapter}:$span")
                previous!!.bookId != first.bookId -> append("; ${bookName(first.bookId)} ${first.chapter}:$span")
                previous!!.chapter != first.chapter -> append("; ${first.chapter}:$span")
                else -> append(", $span")
            }
            previous = run.last()
        }
    }
}

/** A cited passage as text, e.g. "Juan 3:16", "Juan 3:16-18" or a whole chapter "Salmos 23" —
 *  how a Bible Helper verse card names it (#129). */
fun formatVerseReference(reference: VerseReference, bookName: (Int) -> String): String {
    val chapter = "${bookName(reference.bookId)} ${reference.chapter}"
    val start = reference.startVerse ?: return chapter
    val end = reference.endVerse?.takeIf { it > start }
    return if (end == null) "$chapter:$start" else "$chapter:$start-$end"
}

/**
 * [bookId]'s name in translation [versionId] itself. A highlight or note is labelled in the
 * translation it was made in, matching the verse text shown with it, whatever version is selected
 * now (#54). [namesByVersion] holds each version's stored names; the English name stands in for the
 * bundled KJV and for a version with none stored (downloaded before names were kept, or removed).
 */
fun bookNameInVersion(versionId: String, bookId: Int, namesByVersion: Map<String, Map<Int, String>>): String =
    namesByVersion[versionId]?.get(bookId) ?: BibleBooks.getBookById(bookId)?.name.orEmpty()

/**
 * The text Copy/Share hand off for a set of verses: the scripture in quotes (verse numbers
 * inline only when there's more than one), then the full reference and version, e.g.
 * `"Les digo, amigos míos…" — Lucas 12:4 (PDT)`. [textOf] returns null for a verse whose text
 * isn't loaded; those are skipped rather than left as gaps.
 */
fun buildVerseShareText(
    verses: List<VerseLocationDto>,
    textOf: (VerseLocationDto) -> String?,
    bookName: (Int) -> String,
    versionLabel: String
): String {
    val sorted = verses.distinct().sortedWith(compareBy({ it.bookId }, { it.chapter }, { it.verse }))
    val body = sorted.mapNotNull { location ->
        textOf(location)?.trim()?.takeIf { it.isNotEmpty() }?.let { if (sorted.size > 1) "${location.verse} $it" else it }
    }.joinToString(" ")
    return "\"$body\"\n— ${formatFullVerseRefs(sorted, bookName)} ($versionLabel)"
}
