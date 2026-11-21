package com.application.bibleapp.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One run of verse text with the formatting the source API attaches to it.
 * [poemLevel]/[lineBreakBefore] are captured now (stage 1) even though only
 * [isWordsOfJesus] is rendered yet — capturing the whole shape once avoids a
 * second re-download when poem rendering (stage 2) lands. [footnoteId] is set
 * when a footnote marker immediately follows this run's text (stage 4) —
 * null for a run with no attached footnote, and for every run in content
 * downloaded before stage 4 (old rows just render with no marker).
 *
 * [paragraphBreakBefore] (stage 5) is `true` when this run's source text began
 * with a pilcrow (¶) — the source API has no structured paragraph field; the
 * pilcrow, baked into the plain text by this translation's own formatting, is
 * the only paragraph signal that exists. The parser strips the glyph itself
 * out of [text] and turns it into this flag instead. `false`/absent for every
 * run parsed before stage 5.
 */
@Serializable
data class VerseRun(
    val text: String,
    val isWordsOfJesus: Boolean = false,
    val poemLevel: Int? = null,
    val lineBreakBefore: Boolean = false,
    val footnoteId: Int? = null,
    val paragraphBreakBefore: Boolean = false,
    /** Continues the poetry line the run before it started: a footnote marker split the line,
     *  but it stays one line (YouVersion text, #366). bible.helloao.org runs never set it. */
    val sameLine: Boolean = false
)

/** A heading or Hebrew subtitle that appears immediately before a verse. */
@Serializable
data class StoredHeading(
    val type: String, // "heading" or "hebrew_subtitle"
    val text: String
)

/** One footnote's text, looked up separately from verse rendering by (version, book, chapter, noteId). */
data class Footnote(
    val noteId: Int,
    val verse: Int,
    val caller: String?,
    val text: String
)

/**
 * Rich (source-agnostic) representation of one verse's content, stored
 * alongside the plain-text column so search/sharing/etc. are unaffected.
 * [headings] is populated starting stage 3; empty until then.
 */
@Serializable
data class StoredVerseContent(
    val headings: List<StoredHeading> = emptyList(),
    val runs: List<VerseRun>
)

private val verseContentJson = Json { ignoreUnknownKeys = true }

fun StoredVerseContent.encodeToJson(): String =
    verseContentJson.encodeToString(StoredVerseContent.serializer(), this)

/** Null for legacy rows downloaded before rich content existed, or on any parse failure. */
fun decodeVerseContentOrNull(raw: String?): StoredVerseContent? {
    if (raw.isNullOrBlank()) return null
    return try {
        verseContentJson.decodeFromString(StoredVerseContent.serializer(), raw)
    } catch (e: Exception) {
        null
    }
}
