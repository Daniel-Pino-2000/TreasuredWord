package com.application.bibleapp.data.model

import com.application.bibleapp.data.remote.AiPassageDto
import com.application.bibleapp.data.remote.VerseLocationDto
import com.application.bibleapp.utils.formatFullVerseRefs

/** The contract's limit for a passage's text (docs/api_contract.md, #132). */
const val AI_PASSAGE_MAX_CHARS = 2_000

/**
 * Verses attached to a Bible Helper question with "Ask about this verse" (#132). [label] is how
 * the chip names them, in the version's book names with its abbreviation ("Juan 3:16-18 (RV1909)");
 * [reference] is USFM, and [text] the verses as the conversation shows them, numbers inline.
 *
 * [licensedVersionId] is set when they're from a licensed version (#383): its text may be shown
 * next to an answer but never sent to the AI (docs/bible-licensing.md), so [dto], what the server
 * gets, then carries only the reference and the version's name.
 */
data class AiQuestionPassage(
    val label: String,
    val reference: String,
    val text: String,
    val licensedVersionId: String? = null
) {
    val dto: AiPassageDto
        get() = if (licensedVersionId == null) {
            AiPassageDto(reference, text)
        } else {
            AiPassageDto(reference, version = LicensedBibles.abbreviation(licensedVersionId))
        }

    /** The licensed version's publisher site, to link its name to (Lockman's tag rule, #383); null otherwise. */
    val publisherUrl: String?
        get() = licensedVersionId?.let { LicensedBibles.publisherUrl(it) }
}

/**
 * The passage for the reader's [selection], or null when none of it has text. Only the first
 * selected verse's chapter counts, since the server takes a passage within one chapter (the
 * reader shows one chapter at a time anyway). [textOf] gives a verse's text in the selected
 * version; the text is cut at a word to fit [AI_PASSAGE_MAX_CHARS].
 */
fun buildAiQuestionPassage(
    selection: Collection<VerseLocationDto>,
    textOf: (VerseLocationDto) -> String?,
    bookName: (Int) -> String,
    versionLabel: String,
    licensedVersionId: String? = null
): AiQuestionPassage? {
    val sorted = selection.distinct().sortedWith(compareBy({ it.bookId }, { it.chapter }, { it.verse }))
    val first = sorted.firstOrNull() ?: return null
    val code = UsfmBookCodes.code(first.bookId) ?: return null
    val verses = sorted.filter { it.bookId == first.bookId && it.chapter == first.chapter }
        .mapNotNull { location -> textOf(location)?.trim()?.takeIf { it.isNotEmpty() }?.let { location to it } }
    if (verses.isEmpty()) return null

    val numbered = verses.size > 1
    val text = verses.joinToString(" ") { (location, text) -> if (numbered) "${location.verse} $text" else text }
    return AiQuestionPassage(
        label = "${formatFullVerseRefs(verses.map { it.first }, bookName)} ($versionLabel)",
        reference = "$code ${first.chapter}:${verseRuns(verses.map { it.first.verse })}",
        text = cutToLength(text, AI_PASSAGE_MAX_CHARS),
        licensedVersionId = licensedVersionId
    )
}

/** Sorted verse numbers as the contract's runs: [16, 17, 18, 20] → "16-18,20". */
private fun verseRuns(numbers: List<Int>): String {
    val runs = mutableListOf<IntRange>()
    numbers.forEach { n ->
        val last = runs.lastOrNull()
        if (last != null && n == last.last + 1) runs[runs.lastIndex] = last.first..n else runs += n..n
    }
    return runs.joinToString(",") { if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}" }
}

/** [text] when it fits [max], otherwise cut at the last space before it, with "…". */
private fun cutToLength(text: String, max: Int): String {
    if (text.length <= max) return text
    val cut = text.take(max - 1)
    val lastSpace = cut.lastIndexOf(' ')
    return (if (lastSpace >= max / 2) cut.take(lastSpace) else cut).trimEnd() + "…"
}
