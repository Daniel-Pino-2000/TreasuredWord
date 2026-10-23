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
}
