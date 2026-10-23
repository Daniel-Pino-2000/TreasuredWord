package com.application.bibleapp.components

import org.junit.Assert.assertEquals
import org.junit.Test

class VerseScrollTargetTest {

    // John 4 as Twi (ASA) groups it: verses 1-6 run together as one paragraph (#57).
    private val paragraphs = listOf(listOf(1, 2, 3, 4, 5, 6), listOf(7, 8), listOf(9, 10, 11))

    @Test
    fun `the first verse of the chapter shows the chapter title`() {
        assertEquals(VerseScrollTarget(paragraphIndex = 0, listItemIndex = 0, insideParagraph = false), verseScrollTarget(paragraphs, 1))
    }

    @Test
    fun `a verse deep in the first paragraph goes on past the title to the verse`() {
        assertEquals(VerseScrollTarget(paragraphIndex = 0, listItemIndex = 1, insideParagraph = true), verseScrollTarget(paragraphs, 6))
    }

    @Test
    fun `a paragraph's first verse just brings that paragraph to the top`() {
        assertEquals(VerseScrollTarget(paragraphIndex = 1, listItemIndex = 2, insideParagraph = false), verseScrollTarget(paragraphs, 7))
    }

    @Test
    fun `a later verse in a later paragraph scrolls on to the verse`() {
        assertEquals(VerseScrollTarget(paragraphIndex = 2, listItemIndex = 3, insideParagraph = true), verseScrollTarget(paragraphs, 11))
    }

    @Test
    fun `a verse that isn't in the chapter falls back to the start without scrolling further`() {
        assertEquals(VerseScrollTarget(paragraphIndex = 0, listItemIndex = 0, insideParagraph = false), verseScrollTarget(paragraphs, 40))
    }
}
