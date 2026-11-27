package com.application.bibleapp.data.model

import com.application.bibleapp.data.model.EsvStorage.SavedChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Crossway lets the phone keep at most 500 ESV verses, and never more than half of a book (#377). */
class EsvStorageTest {

    private val bigBook = { _: Int -> 2_000 }

    @Test
    fun `a chapter more than half of its book is never saved`() {
        assertFalse(EsvStorage.canStore(verses = 25, bookVerses = 25)) // a one-chapter book, like Jude
        assertFalse(EsvStorage.canStore(verses = 23, bookVerses = 38)) // Haggai 2
        assertTrue(EsvStorage.canStore(verses = 15, bookVerses = 38)) // Haggai 1
        assertTrue(EsvStorage.canStore(verses = 19, bookVerses = 38)) // exactly half
    }

    @Test
    fun `nothing is dropped while the saved chapters keep to the limits`() {
        val saved = listOf(SavedChapter(43, 1, 51), SavedChapter(43, 2, 25), SavedChapter(19, 23, 6))

        assertEquals(emptyList<SavedChapter>(), EsvStorage.toEvict(saved, bigBook))
    }

    @Test
    fun `past 500 verses the oldest chapters go first`() {
        val saved = (1..12).map { SavedChapter(19, it, 45) } // 540 verses, the oldest first

        val evicted = EsvStorage.toEvict(saved, bigBook)

        assertEquals(listOf(SavedChapter(19, 1, 45)), evicted)
        assertTrue(saved.minus(evicted.toSet()).sumOf { it.verses } <= EsvBible.MAX_STORED_VERSES)
    }

    @Test
    fun `no more than half of a book stays, the newest chapters kept`() {
        // Ruth: 22 + 23 + 18 + 22 = 85 verses, so at most 42 may be saved.
        val ruth = mapOf(1 to 22, 2 to 23, 3 to 18, 4 to 22)
        val saved = listOf(SavedChapter(8, 1, 22), SavedChapter(8, 2, 23), SavedChapter(8, 3, 18))

        val evicted = EsvStorage.toEvict(saved) { if (it == 8) ruth.values.sum() else 2_000 }

        // Ruth 3 (18) and 2 (23) make 41; Ruth 1 would pass the half.
        assertEquals(listOf(SavedChapter(8, 1, 22)), evicted)
    }

    @Test
    fun `an older chapter that still fits stays when a newer one doesn't`() {
        val saved = listOf(SavedChapter(19, 1, 6), SavedChapter(19, 119, 176)) +
            (1..10).map { SavedChapter(1, it, 30) } + SavedChapter(43, 3, 36) // 518 verses, the oldest first

        val evicted = EsvStorage.toEvict(saved, bigBook)

        // Newest first: John 3 and Genesis 1–10 make 336; Psalm 119 would make 512; Psalm 1 makes 342.
        assertEquals(listOf(SavedChapter(19, 119, 176)), evicted)
    }
}
