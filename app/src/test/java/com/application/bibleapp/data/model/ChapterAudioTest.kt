package com.application.bibleapp.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterAudioTest {

    private val john3 = ChapterAudioItem("BSB", bookId = 43, chapter = 3, narrator = AudioNarrator.SOUER)

    @Test
    fun `builds HelloAO's address for a BSB chapter`() {
        assertEquals("https://audio.bible.helloao.org/api/BSB/JHN/3/audio/souer.mp3", ChapterAudio.url(john3))
        assertEquals(
            "https://audio.bible.helloao.org/api/BSB/1SA/17/audio/hays.mp3",
            ChapterAudio.url(ChapterAudioItem("BSB", 9, 17, AudioNarrator.HAYS))
        )
    }

    @Test
    fun `only BSB has audio until the WordProject recordings are cleared`() {
        assertTrue(ChapterAudio.hasAudio("BSB"))
        assertFalse(ChapterAudio.hasAudio("kjv"))
        assertFalse(ChapterAudio.hasAudio("eng_kjv"))
        assertFalse(ChapterAudio.hasAudio("spa_r09"))
        assertNull(ChapterAudio.url(john3.copy(versionId = "eng_kjv")))
    }

    @Test
    fun `refuses chapters outside the 66 books`() {
        assertNull(ChapterAudio.url(john3.copy(chapter = 22)))
        assertNull(ChapterAudio.url(john3.copy(chapter = 0)))
        assertNull(ChapterAudio.url(john3.copy(bookId = 67)))
    }

    @Test
    fun `next goes on to the following chapter, then the next book, and stops after Revelation`() {
        assertEquals(john3.copy(chapter = 4), ChapterAudio.next(john3))
        assertEquals(john3.copy(bookId = 44, chapter = 1), ChapterAudio.next(john3.copy(chapter = 21)))
        assertNull(ChapterAudio.next(john3.copy(bookId = 66, chapter = 22)))
    }

    @Test
    fun `a media id reads back as the same chapter, and anything else is refused`() {
        assertEquals(john3, ChapterAudio.parseMediaId(john3.mediaId))
        assertNull(ChapterAudio.parseMediaId(null))
        assertNull(ChapterAudio.parseMediaId("BSB:43:3:david"))
        assertNull(ChapterAudio.parseMediaId("eng_kjv:43:3:souer"))
        assertNull(ChapterAudio.parseMediaId("BSB:43:3"))
    }

    @Test
    fun `the remembered narrator is kept only when they read that version`() {
        assertEquals(AudioNarrator.HAYS, ChapterAudio.narratorFor("BSB", AudioNarrator.HAYS))
        assertEquals(AudioNarrator.SOUER, ChapterAudio.narratorFor("BSB", null))
        assertNull(ChapterAudio.narratorFor("kjv", AudioNarrator.HAYS))
    }
}
