package com.application.bibleapp.server.search.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading Nave's Topical Bible's CSV (#394). The rows below are written in its format (references
 * only, so nothing of the source is copied); the real file isn't in the repo.
 */
class NavesTopicsTest {

    private val csv = "﻿section,subject,entry\r\n" +
        "E,ENVY,\"-General scriptures concerning JOB 5:2,3; PSA 37:1,7; 73:3,17-20; So 8:6\n" +
        "-INSTANCES OF\n" +
        "     -Cain, of Abel GEN 4:4-8\n" +
        "     -Joseph's brethren, of Joseph GEN 37:4-11,19,20; ACT 7:9\n" +
        "-See JEALOUSY\"\r\n" +
        "P,PRIESTHOOD,\"-Consecration of EXO 28; 29; LEV 8\"\r\n"

    @Test
    fun entriesGetTheirTopicAndParentLabel() {
        val entries = NavesTopics.parse(csv)

        assertEquals(
            listOf("Envy", "Envy, instances of: Cain, of Abel", "Envy, instances of: Joseph's brethren, of Joseph", "Priesthood: Consecration of"),
            entries.map { it.text }
        )
        // "See JEALOUSY" and "INSTANCES OF" have no verses of their own, so they aren't entries.
        assertTrue(entries.all { it.passages.isNotEmpty() })
    }

    @Test
    fun aChapterWithoutABookContinuesTheLastBook() {
        val general = NavesTopics.parse(csv).first().passages

        assertEquals(
            listOf(
                TopicPassage(18, 5, 2, 2), TopicPassage(18, 5, 3, 3),
                TopicPassage(19, 37, 1, 1), TopicPassage(19, 37, 7, 7),
                TopicPassage(19, 73, 3, 3), TopicPassage(19, 73, 17, 20),
                // Nave's writes Song of Songs "So".
                TopicPassage(22, 8, 6, 6)
            ),
            general
        )
    }

    @Test
    fun wholeChaptersAndRangesIntoTheNextChapter() {
        assertEquals(
            listOf(TopicPassage(2, 28, null, null), TopicPassage(2, 29, null, null), TopicPassage(3, 8, null, null)),
            NavesTopics.passages("EXO 28; 29; LEV 8")
        )
        // "6:13-7:5" keeps 6:13 only rather than a backwards range.
        assertEquals(listOf(TopicPassage(1, 6, 13, 13)), NavesTopics.passages("GEN 6:13-7:5"))
    }

    @Test
    fun matchingEntriesRankTheirVersesWholeChaptersIncluded() {
        // Three verses in two chapters, two entries on two axes.
        val gen11 = VerseRef(1, 1, 1); val gen12 = VerseRef(1, 1, 2); val gen21 = VerseRef(1, 2, 1)
        val index = VectorIndex(mapOf(gen11 to floatArrayOf(1f, 0f), gen12 to floatArrayOf(0.9f, 0.1f), gen21 to floatArrayOf(0f, 1f)))
        val entries = listOf(
            TopicEntry("A", "A", listOf(TopicPassage(1, 1, null, null))),
            TopicEntry("B", "B", listOf(TopicPassage(1, 2, 1, 1)))
        )
        val topics = TopicIndex(entries, listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), index)

        val rows = topics.verseRows(floatArrayOf(1f, 0.05f), threshold = 0.9f, maxEntries = 3)
        assertEquals(setOf(index.rows.getValue(gen11), index.rows.getValue(gen12)), rows)
        assertEquals(emptySet(), topics.verseRows(floatArrayOf(1f, 1f), threshold = 0.99f, maxEntries = 3))
        assertEquals("A", topics.best(floatArrayOf(1f, 0f))?.first?.text)
    }
}
