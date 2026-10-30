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
 */
suspend fun loadAiVersePassage(
    reference: VerseReference,
    selectedVersionId: String,
    chapterOf: suspend (versionId: String) -> List<VerseUI>
): AiVersePassage? {
    val wanted = reference.startVerse?.let { start -> start..(reference.endVerse ?: start) }

    fun versesIn(chapter: List<VerseUI>): List<PassageVerse> = chapter.mapNotNull { verse ->
        val number = verse.verse ?: return@mapNotNull null
        val text = verse.text.removePrefix(LEGACY_PARAGRAPH_MARKER).trim()
        if (text.isEmpty() || (wanted != null && number !in wanted)) null else PassageVerse(number, text)
    }

    fun isComplete(verses: List<PassageVerse>): Boolean =
        if (wanted == null) verses.isNotEmpty() else verses.map { it.number }.containsAll(wanted.toList())

    val selected = versesIn(chapterOf(selectedVersionId))
    if (isComplete(selected) || selectedVersionId == DEFAULT_VERSION.id) {
        return selected.takeIf { it.isNotEmpty() }?.let { AiVersePassage(selectedVersionId, it) }
    }
    val kjv = versesIn(chapterOf(DEFAULT_VERSION.id))
    return when {
        isComplete(kjv) -> AiVersePassage(DEFAULT_VERSION.id, kjv)
        selected.isNotEmpty() -> AiVersePassage(selectedVersionId, selected)
        kjv.isNotEmpty() -> AiVersePassage(DEFAULT_VERSION.id, kjv)
        else -> null
    }
}

/** A stray pilcrow some plain-text verses start with (see BibleText.kt). */
private const val LEGACY_PARAGRAPH_MARKER = "¶ "
