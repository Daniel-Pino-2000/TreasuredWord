package com.application.bibleapp.server.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The topic guides pilot's file parses, and every passage is a reference the app shows. */
class TopicGuidesTest {

    @Test
    fun everyGuideParsesWithPhrasesPointsAndPassages() {
        val guides = TopicGuides.load()
        assertTrue(guides.size >= 40)
        assertEquals(guides.size, guides.map { it.id }.toSet().size, "ids are unique")
        for (guide in guides) {
            assertTrue(guide.asks.size >= 2 && guide.points.isNotEmpty() && guide.passages.size >= 3, guide.id)
            for ((reference, note) in guide.passages) {
                assertTrue(reference.substringBefore(' ') in UsfmBookCodes.codes, "${guide.id}: $reference")
                assertTrue(note.isNotBlank())
            }
        }
    }

    @Test
    fun theSectionListsPointsAndMarkedPassages() {
        val baptism = TopicGuides.load().single { it.id == "baptism" }
        val section = TopicGuides.section(listOf(RoutedGuide(baptism, 0.8)))!!
        assertTrue(section.startsWith("# 9. A guide for this question"))
        assertTrue("- [[LUK 23:42-43]]: the thief on the cross" in section)
        assertEquals(null, TopicGuides.section(emptyList()))
    }

    @Test
    fun aSecondGuideOnlyWhenCloseToTheFirst() {
        val (a, b, c) = TopicGuides.load().take(3)
        val router = TopicRouter(emptyList(), com.application.bibleapp.server.search.FakeEmbeddingProvider(), threshold = 0.6)
        assertEquals(listOf(a), router.pick(listOf(RoutedGuide(a, 0.80), RoutedGuide(b, 0.70))).map { it.guide })
        assertEquals(listOf(a, b), router.pick(listOf(RoutedGuide(a, 0.80), RoutedGuide(b, 0.78))).map { it.guide })
        assertEquals(emptyList(), router.pick(listOf(RoutedGuide(c, 0.59))))
    }
}
