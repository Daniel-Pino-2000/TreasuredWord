package com.application.bibleapp.data.model

import com.application.bibleapp.utils.VerseReference

/** The verses one Bible Helper verse card shows (#129), and the translation they came from. */
data class AiVersePassage(val versionId: String, val verses: List<PassageVerse>)

data class PassageVerse(val number: Int, val text: String)

/**
 * The text of [reference] from the user's selected translation, or from the bundled KJV when the
 * selected one doesn't have all of it (an Old Testament verse in a New Testament-only translation,
 * a verse it leaves out) — whole, never a mix of the two (#129). The words always come from a
 * Bible on the phone, never from the AI. [chapterOf] loads the reference's chapter in a version
 * (BibleRepository.getChapter). Null when neither has any of it.
 *
 * A licensed version read through YouVersion (#383) is shown too: its terms allow Scripture next
 * to an AI answer when it's shown word for word (docs/bible-licensing.md), never sent to the AI.
 * [chapterOf] fetches its chapter when it isn't saved; offline or on a 429 it comes back empty, and
 * the passage is the KJV's.
 *
 * A version whose terms bar any AI use of its text, such as the ESV (#377,
 * [LicensedBibles.allowsAi]), is never shown next to an answer: the passage is the KJV's.
 */
suspend fun loadAiVersePassage(
    reference: VerseReference,
    readerVersionId: String,
    chapterOf: suspend (versionId: String) -> List<VerseUI>
): AiVersePassage? {
    val selectedVersionId = readerVersionId.takeIf { LicensedBibles.allowsAi(it) } ?: DEFAULT_VERSION.id
    val selected = passageIn(chapterOf(selectedVersionId), reference)
    if (isComplete(selected, reference) || selectedVersionId == DEFAULT_VERSION.id) {
        return selected.takeIf { it.isNotEmpty() }?.let { AiVersePassage(selectedVersionId, it) }
    }
    val kjv = passageIn(chapterOf(DEFAULT_VERSION.id), reference)
    return when {
        isComplete(kjv, reference) -> AiVersePassage(DEFAULT_VERSION.id, kjv)
        selected.isNotEmpty() -> AiVersePassage(selectedVersionId, selected)
        kjv.isNotEmpty() -> AiVersePassage(DEFAULT_VERSION.id, kjv)
        else -> null
    }
}

/** [reference]'s verses in [chapter], a verse stored as several rows joined back together. */
fun passageIn(chapter: List<VerseUI>, reference: VerseReference): List<PassageVerse> {
    val wanted = reference.startVerse?.let { start -> start..(reference.endVerse ?: start) }
    val texts = linkedMapOf<Int, MutableList<String>>()
    chapter.forEach { verse ->
        val number = verse.verse ?: return@forEach
        val text = verse.text.removePrefix(LEGACY_PARAGRAPH_MARKER).trim()
        if (text.isNotEmpty() && (wanted == null || number in wanted)) texts.getOrPut(number) { mutableListOf() } += text
    }
    return texts.map { (number, parts) -> PassageVerse(number, parts.joinToString(" ")) }
}

/** Whether [verses] are all of [reference]: every verse of a range, or any of a whole chapter. */
private fun isComplete(verses: List<PassageVerse>, reference: VerseReference): Boolean {
    val start = reference.startVerse ?: return verses.isNotEmpty()
    return verses.map { it.number }.containsAll((start..(reference.endVerse ?: start)).toList())
}

/** A stray pilcrow some plain-text verses start with (see BibleText.kt). */
private const val LEGACY_PARAGRAPH_MARKER = "¶ "
