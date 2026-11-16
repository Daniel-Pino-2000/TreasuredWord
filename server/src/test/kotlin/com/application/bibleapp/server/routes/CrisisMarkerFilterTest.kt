package com.application.bibleapp.server.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [CrisisMarkerFilter] (#285): the marker never reaches a streamed answer, however it's split. */
class CrisisMarkerFilterTest {

    private fun filtered(vararg pieces: String): Pair<List<String>, Boolean> {
        val filter = CrisisMarkerFilter()
        val shown = pieces.map { filter.push(it) } + filter.finish()
        return shown to filter.crisis
    }

    @Test
    fun anOrdinaryAnswerPassesStraightThrough() {
        val (shown, crisis) = filtered("Grace is ", "a gift [[EPH 2:8]].")

        assertEquals(listOf("Grace is ", "a gift [[EPH 2:8]].", ""), shown)
        assertFalse(crisis)
    }

    @Test
    fun aMarkerSplitAcrossPiecesIsHeldBackAndRemoved() {
        val (shown, crisis) = filtered("[[CR", "ISIS]]", "\n\nYou matter to God.")

        assertEquals(listOf("", "", "You matter to God.", ""), shown)
        assertTrue(crisis)
    }

    @Test
    fun aMarkerAndTextInOnePieceGiveTheTextAlone() {
        val (shown, crisis) = filtered("[[CRISIS]] Please call ", "someone now.")

        assertEquals("Please call someone now.", shown.joinToString(""))
        assertTrue(crisis)
    }

    @Test
    fun aMarkerLaterInTheAnswerIsRemovedToo() {
        val (shown, crisis) = filtered("Please call someone. [", "[CRISIS]] You matter.")

        assertEquals("Please call someone.  You matter.", shown.joinToString(""))
        assertTrue(crisis)
    }

    @Test
    fun aVerseReferenceIsNotHeldBack() {
        val filter = CrisisMarkerFilter()

        assertEquals("See [[J", filter.push("See [[J"))
        // Only "[[C…" could still become the marker.
        assertEquals("See ", filter.push("See [[C"))
        assertEquals("[[COL 3:12]]", filter.push("OL 3:12]]"))
        assertFalse(filter.crisis)
    }

    @Test
    fun whatLooksLikeAMarkerAtTheEndIsShownWhenTheAnswerEnds() {
        val (shown, crisis) = filtered("It ends with [[")

        assertEquals("It ends with [[", shown.joinToString(""))
        assertFalse(crisis)
    }

    @Test
    fun anAnswerThatIsOnlyTheMarkerShowsNothing() {
        val (shown, crisis) = filtered("[[CRISIS]]")

        assertEquals("", shown.joinToString(""))
        assertTrue(crisis)
    }
}
