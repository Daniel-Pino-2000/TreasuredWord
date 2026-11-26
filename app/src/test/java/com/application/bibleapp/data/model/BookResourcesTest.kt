package com.application.bibleapp.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookResourcesTest {

    private val allBooks = 1..66
    private val youTubeId = Regex("[A-Za-z0-9_-]{11}")

    @Test
    fun `every book has a GotQuestions summary page`() {
        for (bookId in allBooks) {
            val url = BookResources.summaryUrl(bookId)
            assertTrue("book $bookId: $url", url != null && url.startsWith("https://www.gotquestions.org/") && url.endsWith(".html"))
        }
        assertEquals(allBooks.toSet(), BookResources.summaryPages.keys)
    }

    @Test
    fun `the irregular summary pages keep their own names`() {
        assertEquals("https://www.gotquestions.org/Song-of-Solomon.html", BookResources.summaryUrl(22))
        assertEquals("https://www.gotquestions.org/Gospel-of-John.html", BookResources.summaryUrl(43))
        assertEquals("https://www.gotquestions.org/Book-of-1-Kings.html", BookResources.summaryUrl(11))
    }

    @Test
    fun `every book has English and Spanish overview videos with well-formed IDs`() {
        for (videos in listOf(BookResources.englishVideos, BookResources.spanishVideos)) {
            assertEquals(allBooks.toSet(), videos.keys)
            for ((bookId, ids) in videos) {
                assertTrue("book $bookId has ${ids.size} videos", ids.size in 1..2)
                ids.forEach { assertTrue("book $bookId: $it", youTubeId.matches(it)) }
            }
        }
    }

    @Test
    fun `the two languages never share a video`() {
        val english = BookResources.englishVideos.values.flatten().toSet()
        val spanish = BookResources.spanishVideos.values.flatten().toSet()
        assertTrue((english intersect spanish).isEmpty())
    }

    @Test
    fun `a video shared between books covers a run of consecutive books`() {
        for ((videos, spanish) in listOf(BookResources.englishVideos to false, BookResources.spanishVideos to true)) {
            for ((bookId, ids) in videos) {
                for (id in ids) {
                    val covered = BookResources.booksCovered(id, bookId, spanish)
                    assertTrue("$id covers $covered", bookId in covered)
                    // Every book in the range lists the same video, so nothing in between is skipped.
                    covered.forEach { assertTrue("$id missing from book $it", id in videos.getValue(it)) }
                }
            }
        }
    }

    @Test
    fun `shared overviews span the books they're named for`() {
        val kings = BookResources.overviewVideos(11, spanish = false).single()
        assertEquals(11..12, BookResources.booksCovered(kings, 11, spanish = false))
        val letters = BookResources.overviewVideos(63, spanish = false).single()
        assertEquals(62..64, BookResources.booksCovered(letters, 63, spanish = false))
        val genesis = BookResources.overviewVideos(1, spanish = false).first()
        assertEquals(1..1, BookResources.booksCovered(genesis, 1, spanish = false))
    }

    @Test
    fun `Spanish picks the Spanish video`() {
        assertEquals(BookResources.spanishVideos[1], BookResources.overviewVideos(1, spanish = true))
        assertEquals(BookResources.englishVideos[1], BookResources.overviewVideos(1, spanish = false))
    }
    @Test
    fun `More overviews are the next books, each video once`() {
        assertEquals(listOf(44, 45, 46), BookResources.nextOverviews(43, spanish = false).map { it.first })
        // 1–2 Kings share a video, so 1 Kings is followed by 1 Chronicles; 1–3 John, by Jude.
        assertEquals(13, BookResources.nextOverviews(11, spanish = false).first().first)
        assertEquals(65, BookResources.nextOverviews(62, spanish = false).first().first)
        // After Revelation it wraps to Genesis.
        assertEquals(listOf(1, 2, 3), BookResources.nextOverviews(66, spanish = false).map { it.first })
        for (spanish in listOf(false, true)) for (book in 1..66) {
            val more = BookResources.nextOverviews(book, spanish)
            val ids = more.map { it.second }
            assertEquals("book $book", 3, more.size)
            assertEquals("book $book repeats a video", ids.size, ids.toSet().size)
            assertTrue("book $book lists its own video", ids.none { it in BookResources.overviewVideos(book, spanish) })
            more.forEach { (b, id) -> assertEquals(BookResources.overviewVideos(b, spanish).first(), id) }
        }
    }

    private fun passages(bookId: Int, chapter: Int, spanish: Boolean = false) =
        BookResources.passageVideos(bookId, chapter, spanish)

    @Test
    fun `a passage video shows on the first and last chapter of its range, not outside it`() {
        // Acts 13–20, Paul's Missionary Journeys.
        val journeys = BookResources.passageEntries.single { it.bookId == 44 && it.chapters == 13..20 }
        for (chapter in listOf(13, 16, 20)) {
            assertEquals("Acts $chapter", listOf(journeys.english), passages(44, chapter).map { it.videoId })
        }
        assertTrue(passages(44, 12).none { it.videoId == journeys.english })
        assertTrue(passages(44, 21).none { it.videoId == journeys.english })
        // A one-chapter video: Psalm 8, and nothing for Psalm 7 or 9.
        assertEquals(8..8, passages(19, 8).single().chapters)
        assertTrue(passages(19, 7).isEmpty())
        assertTrue(passages(19, 9).isEmpty())
        // Matthew 5–7's commentary, but not Matthew 4 or 8.
        assertTrue(passages(40, 7).any { it.chapters == 5..7 })
        assertTrue(passages(40, 4).isEmpty())
        assertTrue(passages(40, 8).isEmpty())
    }

    @Test
    fun `a book with several passage videos picks the ones for the chapter`() {
        val luke = { chapter: Int -> passages(42, chapter).map { it.chapters } }
        assertEquals(listOf(1..2), luke(1))
        assertEquals(listOf(3..9), luke(5))
        assertEquals(listOf(24..24), luke(24))
        // Where BibleProject's ranges meet, both videos, in reading order.
        assertEquals(listOf(3..9, 9..19), luke(9))
        assertEquals(listOf(9..19, 19..23), luke(19))
        // Psalms has three, far apart.
        assertEquals(148..148, passages(19, 148).single().chapters)
        assertEquals(1..1, passages(19, 1).single().chapters)
        // Exodus: the 10 Commandments in 20, the Character of God in 34, nothing in between.
        assertEquals(11, passages(2, 20).size)
        assertEquals(6, passages(2, 34).size)
        assertTrue(passages(2, 21).isEmpty())
        // A book with none.
        assertTrue((1..22).all { passages(66, it).isEmpty() })
    }

    @Test
    fun `whole-chapter videos come first, then verses in order, a series in episode order`() {
        // Matthew 5: the 5–7 commentary, then 3-12, 3-16, 17-20, 21-32, 33-48.
        val matthew5 = passages(40, 5)
        assertEquals(5..7, matthew5.first().chapters)
        assertEquals(null, matthew5.first().verses)
        assertEquals(listOf(3..12, 3..16, 17..20, 21..32, 33..48), matthew5.drop(1).map { it.verses })
        // Exodus 20: the whole commandments, then each in order.
        assertEquals(listOf(1, 2, 4, 7, 8, 12, 13, 14, 15, 16, 17), passages(2, 20).map { it.verses!!.first })
        // The Character of God keeps its episode order: the commentary first.
        val exodus34 = BookResources.passageEntries.filter { it.bookId == 2 && it.chapters == 34..34 }.map { it.english }
        assertEquals(exodus34, passages(2, 34).map { it.videoId })
        assertEquals("nxwzq1PJImM", exodus34.first())
    }

    @Test
    fun `the passage reads like the app's other references`() {
        val video = { chapters: IntRange, verses: IntRange? -> PassageVideo(1, chapters, verses, "abcdefghijk") }
        assertEquals("Luke 1–2", video(1..2, null).reference("Luke"))
        assertEquals("Psalms 8", video(8..8, null).reference("Psalms"))
        assertEquals("Exodus 20:8-11", video(20..20, 8..11).reference("Exodus"))
        assertEquals("Exodus 20:7", video(20..20, 7..7).reference("Exodus"))
    }

    @Test
    fun `Spanish uses Proyecto Biblia's version and falls back to English where there is none`() {
        assertEquals("1wRqEhIMaAw", passages(40, 7, spanish = true).first().videoId)
        assertEquals("ajwehw_AT0s", passages(40, 7).first().videoId)
        // Proyecto Biblia has no episode 9 of the Sermon on the Mount: the English one stands in.
        assertEquals(passages(40, 7).map { it.videoId }.last(), passages(40, 7, spanish = true).map { it.videoId }.last())
        val entries = listOf(
            BookResources.PassageEntry(42, 1..2, null, "aaaaaaaaaaa", "bbbbbbbbbbb"),
            BookResources.PassageEntry(42, 2..3, null, "ccccccccccc", null)
        )
        assertEquals(listOf("bbbbbbbbbbb", "ccccccccccc"), BookResources.passageVideos(entries, 42, 2, spanish = true).map { it.videoId })
        assertEquals(listOf("aaaaaaaaaaa", "ccccccccccc"), BookResources.passageVideos(entries, 42, 2, spanish = false).map { it.videoId })
    }

    @Test
    fun `every passage video is well formed and fits its book`() {
        val english = BookResources.passageEntries.map { it.english }
        val spanish = BookResources.passageEntries.mapNotNull { it.spanish }
        for (entry in BookResources.passageEntries) {
            assertTrue("$entry", entry.chapters.first >= 1 && entry.chapters.last <= BibleBooks.getChapterCount(entry.bookId))
            assertTrue("$entry", !entry.chapters.isEmpty())
            entry.verses?.let { verses ->
                // Verses only for a video about part of one chapter.
                assertEquals("$entry", entry.chapters.first, entry.chapters.last)
                assertTrue("$entry", verses.first >= 1 && verses.last <= BibleBooks.getVerseCount(entry.bookId, entry.chapters.first))
            }
            listOfNotNull(entry.english, entry.spanish).forEach { assertTrue("$entry: $it", youTubeId.matches(it)) }
            // Every book with a passage video has an overview too, so the reader's info icon shows.
            assertTrue("$entry", BookResources.hasAny(entry.bookId))
        }
        assertEquals("a passage video listed twice", english.size, english.toSet().size)
        assertEquals("a passage video listed twice", spanish.size, spanish.toSet().size)
        assertTrue((english.toSet() intersect spanish.toSet()).isEmpty())
        // None of them is a book overview already shown.
        val overviews = (BookResources.englishVideos.values + BookResources.spanishVideos.values).flatten().toSet()
        assertTrue((english + spanish).none { it in overviews })
    }

    @Test
    fun `thumbnails are YouTube's own 16 by 9 stills`() {
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg", BookResources.videoThumbnailUrl("abcdefghijk", large = true))
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/mqdefault.jpg", BookResources.videoThumbnailUrl("abcdefghijk", large = false))
    }
}
