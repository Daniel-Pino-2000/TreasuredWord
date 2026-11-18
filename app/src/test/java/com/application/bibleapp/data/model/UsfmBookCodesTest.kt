package com.application.bibleapp.data.model

import com.application.bibleapp.utils.VerseReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The USFM code table behind the Bible Helper's verse markers (#129). */
class UsfmBookCodesTest {

    @Test
    fun `every one of the 66 books has its own code, in canonical order`() {
        assertEquals(66, UsfmBookCodes.codes.size)
        assertEquals(66, UsfmBookCodes.codes.toSet().size)
        BibleBooks.allBooks.forEach { book ->
            val code = UsfmBookCodes.code(book.id)!!
            assertEquals("${book.name} ($code)", book.id, UsfmBookCodes.bookId(code))
        }
    }

    @Test
    fun `codes land on the right books`() {
        val expected = mapOf(
            "GEN" to "Genesis", "1SA" to "1 Samuel", "PSA" to "Psalms", "SNG" to "Song of Solomon",
            "EZK" to "Ezekiel", "JOL" to "Joel", "NAM" to "Nahum", "MAL" to "Malachi", "MAT" to "Matthew",
            "MRK" to "Mark", "JHN" to "John", "PHP" to "Philippians", "PHM" to "Philemon", "JAS" to "James",
            "1JN" to "1 John", "3JN" to "3 John", "JUD" to "Jude", "REV" to "Revelation"
        )
        expected.forEach { (code, name) ->
            assertEquals(code, name, BibleBooks.getBookById(UsfmBookCodes.bookId(code)!!)!!.name)
        }
    }

    @Test
    fun `codes match in any case, and unknown ones match nothing`() {
        assertEquals(43, UsfmBookCodes.bookId("jhn"))
        assertNull(UsfmBookCodes.bookId("XYZ"))
        assertNull(UsfmBookCodes.bookId("TOB")) // Tobit: a USFM code, but not one of the 66
        assertNull(UsfmBookCodes.code(0))
        assertNull(UsfmBookCodes.code(67))
    }

    @Test
    fun `the server's references read as a verse, a range or a whole chapter`() {
        assertEquals(VerseReference(19, 147, 3), UsfmBookCodes.parseReference("PSA 147:3"))
        assertEquals(VerseReference(40, 11, 28, 30), UsfmBookCodes.parseReference("MAT 11:28-30"))
        assertEquals(VerseReference(19, 73), UsfmBookCodes.parseReference("PSA 73"))
        assertEquals(VerseReference(62, 5, 11, 13), UsfmBookCodes.parseReference(" 1jn 5:11-13 "))
    }

    @Test
    fun `anything else isn't a reference`() {
        listOf("XYZ 1:1", "PSA", "PSA 0", "PSA 23:0", "MAT 11:30-28", "MAT 11:28-", "John 3:16", "PSA 23:1, 2", "").forEach {
            assertNull(it, UsfmBookCodes.parseReference(it))
        }
    }
}
