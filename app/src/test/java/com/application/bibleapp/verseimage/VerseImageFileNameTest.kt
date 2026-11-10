package com.application.bibleapp.verseimage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date

class VerseImageFileNameTest {

    private val time: Date = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.NOVEMBER, 9, 15, 30, 0)
    }.time

    @Test
    fun aReferenceBecomesUnderscores() {
        assertEquals("TreasuredWord_John_3_16_20261109_153000.png", verseImageFileName("John 3:16", time))
    }

    @Test
    fun aRangeAndSeveralChaptersStayReadable() {
        assertEquals(
            "TreasuredWord_Lucas_12_4_6_9_13_1_20261109_153000.png",
            verseImageFileName("Lucas 12:4-6, 9; 13:1", time)
        )
    }

    @Test
    fun lettersOfAnyScriptAreKept() {
        assertEquals("TreasuredWord_요한복음_3_16_20261109_153000.png", verseImageFileName("요한복음 3:16", time))
        assertEquals("TreasuredWord_约翰福音_3_16_20261109_153000.png", verseImageFileName("约翰福音 3:16", time))
    }

    @Test
    fun charactersFileSystemsRejectAreReplaced() {
        val name = verseImageFileName("a/b\\c:d*e?f\"g<h>i|j", time)
        assertFalse(name.any { it in "/\\:*?\"<>|" })
        assertEquals("TreasuredWord_a_b_c_d_e_f_g_h_i_j_20261109_153000.png", name)
    }

    @Test
    fun aLongReferenceIsCut() {
        val name = verseImageFileName("Book ".repeat(40), time)
        assertTrue(name.length <= "TreasuredWord__20261109_153000.png".length + 60)
        assertFalse(name.contains("__"))
    }

    @Test
    fun anEmptyReferenceStillGivesAName() {
        assertEquals("TreasuredWord_20261109_153000.png", verseImageFileName(" : ", time))
    }
}
