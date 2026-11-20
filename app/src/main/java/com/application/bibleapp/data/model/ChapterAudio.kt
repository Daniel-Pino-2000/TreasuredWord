package com.application.bibleapp.data.model

/** Someone who recorded a whole Bible, and the id HelloAO's audio links use for them. */
enum class AudioNarrator(val id: String, val displayName: String) {
    SOUER("souer", "Bob Souer"),
    HAYS("hays", "Barry Hays");

    companion object {
        fun byId(id: String?): AudioNarrator? = entries.firstOrNull { it.id == id }
    }
}

/** One chapter's recording: which version, chapter and narrator. */
data class ChapterAudioItem(
    val versionId: String,
    val bookId: Int,
    val chapter: Int,
    val narrator: AudioNarrator
) {
    /** The player's id for it, read back by [ChapterAudio.parseMediaId]. */
    val mediaId: String get() = "$versionId:$bookId:$chapter:${narrator.id}"
}

/**
 * Chapter audio the reader can play (#367). Hand-checked data, like
 * [BookResources][com.application.bibleapp.data.model.BookResources]: HelloAO serves the MP3s,
 * at the address its chapter responses give in `thisChapterAudioLinks`, so the app builds the same
 * address instead of downloading every chapter's response just for the link.
 *
 * Checked 2026-11-19:
 * - All 1,189 BSB chapters answer for both narrators below (a range request per file).
 * - The BSB audio is public domain (CC0 1.0), as https://bereanbible.com/audio/ says, where its
 *   narrators are named as Bob Souer, Barry Hays and Jordan Gilbert. HelloAO's `souer` and
 *   `hays` files are byte for byte the publisher's own on openbible.com/audio.
 * - Left out: HelloAO's `david` BSB voice (the same as OpenBible's "BSB - David", but not named
 *   on the CC0 page), and the KJV and RV1909 recordings from WordProject, whose terms ask apps not
 *   to stream a whole audio Bible, until HelloAO confirms they may.
 *
 * If HelloAO ever stops serving a file, the player says it couldn't play it; the text is untouched.
 */
object ChapterAudio {

    private const val BASE_URL = "https://audio.bible.helloao.org/api"

    /** The narrators of each version with audio, the first being the default. */
    private val narratorsByVersion: Map<String, List<AudioNarrator>> = mapOf(
        "BSB" to listOf(AudioNarrator.SOUER, AudioNarrator.HAYS)
    )

    /** The version's narrators, empty when it has no audio. */
    fun narrators(versionId: String): List<AudioNarrator> = narratorsByVersion[versionId].orEmpty()

    fun hasAudio(versionId: String): Boolean = narratorsByVersion.containsKey(versionId)

    /** [preferred] when it recorded [versionId], else that version's first narrator. */
    fun narratorFor(versionId: String, preferred: AudioNarrator?): AudioNarrator? {
        val narrators = narrators(versionId)
        return preferred?.takeIf { it in narrators } ?: narrators.firstOrNull()
    }

    /** The MP3's address, or null for a chapter that isn't in the 66 books or a version without audio. */
    fun url(item: ChapterAudioItem): String? {
        if (item.narrator !in narrators(item.versionId)) return null
        val code = UsfmBookCodes.code(item.bookId) ?: return null
        if (item.chapter !in 1..BibleBooks.getChapterCount(item.bookId)) return null
        return "$BASE_URL/${item.versionId}/$code/${item.chapter}/audio/${item.narrator.id}.mp3"
    }

    /** The chapter after [item]'s, by the same narrator, or null after Revelation 22. Every
     *  version with audio has the usual 1,189 chapters, so BibleBooks' counts hold. */
    fun next(item: ChapterAudioItem): ChapterAudioItem? = when {
        item.chapter < BibleBooks.getChapterCount(item.bookId) -> item.copy(chapter = item.chapter + 1)
        item.bookId < BibleBooks.allBooks.size -> item.copy(bookId = item.bookId + 1, chapter = 1)
        else -> null
    }

    fun parseMediaId(mediaId: String?): ChapterAudioItem? {
        val parts = mediaId?.split(':')?.takeIf { it.size == 4 } ?: return null
        val bookId = parts[1].toIntOrNull() ?: return null
        val chapter = parts[2].toIntOrNull() ?: return null
        val narrator = AudioNarrator.byId(parts[3]) ?: return null
        return ChapterAudioItem(parts[0], bookId, chapter, narrator).takeIf { url(it) != null }
    }

    /** The speeds the player offers. */
    val speeds: List<Float> = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
}
