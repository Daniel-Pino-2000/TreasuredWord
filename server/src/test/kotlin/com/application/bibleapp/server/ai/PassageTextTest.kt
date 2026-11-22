package com.application.bibleapp.server.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The reference parsing and joining behind a licensed version's passage (#383); no database. */
class PassageTextTest {

    @Test
    fun everyReferenceShapeTheContractAllowsIsParsed() {
        assertEquals(PassageText.Parsed(43, 3, null), PassageText.parse("JHN 3"))
        assertEquals(PassageText.Parsed(43, 3, setOf(16)), PassageText.parse("JHN 3:16"))
        assertEquals(PassageText.Parsed(62, 1, setOf(9)), PassageText.parse("1JN 1:9"))
        assertEquals(PassageText.Parsed(19, 119, (105..112).toSet()), PassageText.parse("PSA 119:105-112"))
        assertEquals(PassageText.Parsed(42, 12, setOf(4, 6, 7)), PassageText.parse("LUK 12:4,6-7"))
        assertEquals(PassageText.Parsed(1, 1, setOf(1)), PassageText.parse("GEN 1:1"))
        assertEquals(PassageText.Parsed(66, 22, setOf(21)), PassageText.parse("REV 22:21"))
    }

    @Test
    fun aBrokenReferenceIsNull() {
        assertNull(PassageText.parse("XYZ 3:16"))
        assertNull(PassageText.parse("JHN"))
        assertNull(PassageText.parse("JHN 3:18-16"))
        assertNull(PassageText.parse("JHN x:1"))
    }

    @Test
    fun versesAreJoinedAsTheAppSendsThem() {
        assertEquals("For God so loved the world", PassageText.join(listOf(16 to "For God so loved the world"), 2_000))
        assertEquals("16 For God 17 For God did not", PassageText.join(listOf(16 to "For God", 17 to "For God did not"), 2_000))
        assertEquals("one two…", PassageText.join(listOf(1 to "one two three"), 10))
    }
}
