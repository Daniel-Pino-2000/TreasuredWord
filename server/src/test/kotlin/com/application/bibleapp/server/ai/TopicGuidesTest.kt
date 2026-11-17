package com.application.bibleapp.server.ai

import com.application.bibleapp.server.search.FakeEmbeddingProvider
import com.application.bibleapp.server.search.VerseSearch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The topic guides' file parses, every passage is a reference the app shows, and the routing rules (#319). */
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
        assertEquals(listOf("crisis"), guides.filter { it.alone }.map { it.id })
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
    fun theGuidesWordingTheEvaluationAskedFor() {
        // #319: the crisis guide never names the forgiveness question (CRISIS-01 said "I can't
        // answer whether God forgives…"), and alcohol is recommended in the answer's own voice.
        val guides = TopicGuides.load()
        val crisis = TopicGuides.section(listOf(RoutedGuide(guides.single { it.id == "crisis" }, 0.8)))!!
        assertTrue("forgiv" !in crisis.lowercase(), crisis)
        val alcohol = guides.single { it.id == "alcohol" }.points.joinToString(" ")
        assertTrue("your own recommendation" in alcohol && "not as what many Baptists do" in alcohol, alcohol)
    }

    @Test
    fun aSecondGuideOnlyWhenCloseToTheFirst() {
        val (a, b, c) = TopicGuides.load().filter { !it.alone }.take(3)
        val router = TopicRouter(emptyList(), threshold = 0.6)
        assertEquals(listOf(a), router.pick(listOf(RoutedGuide(a, 0.80), RoutedGuide(b, 0.70))).map { it.guide })
        assertEquals(listOf(a, b), router.pick(listOf(RoutedGuide(a, 0.80), RoutedGuide(b, 0.78))).map { it.guide })
        assertEquals(emptyList(), router.pick(listOf(RoutedGuide(c, 0.59))))
    }

    @Test
    fun aCloseCrisisGuideIsSentAloneEvenWhenAnotherScoresHigher() {
        val guides = TopicGuides.load()
        val crisis = guides.single { it.id == "crisis" }
        val forgiveness = guides.single { it.id == "repeated-sin" }
        val router = TopicRouter(emptyList(), threshold = 0.70)
        // The pilot's CRISIS-01: repeated sin 0.79, crisis 0.77, and both were sent.
        assertEquals(listOf(crisis), router.pick(listOf(RoutedGuide(forgiveness, 0.79), RoutedGuide(crisis, 0.77))).map { it.guide })
        assertEquals(listOf(crisis), router.pick(listOf(RoutedGuide(crisis, 0.80), RoutedGuide(forgiveness, 0.79))).map { it.guide })
        // Further behind than a second guide may be, but within ALONE_MARGIN, it still wins.
        assertEquals(listOf(crisis), router.pick(listOf(RoutedGuide(forgiveness, 0.80), RoutedGuide(crisis, 0.745))).map { it.guide })
        // Far behind ("Does God forgive me if I keep looking at porn?": 0.789 and 0.701), or below
        // the threshold, it isn't sent.
        assertEquals(listOf(forgiveness), router.pick(listOf(RoutedGuide(forgiveness, 0.789), RoutedGuide(crisis, 0.701))).map { it.guide })
        assertEquals(listOf(forgiveness), router.pick(listOf(RoutedGuide(forgiveness, 0.99), RoutedGuide(crisis, 0.72))).map { it.guide })
        assertEquals(listOf(forgiveness), router.pick(listOf(RoutedGuide(forgiveness, 0.71), RoutedGuide(crisis, 0.69))).map { it.guide })
    }

    @Test
    fun aRouterMatchesByMeaningWithTheFakeEmbedder() = runBlocking {
        val guides = TopicGuides.parse(
            """
            ## alcohol
            - **Title:** Alcohol
            - **Asks:** Is drinking alcohol a sin? | Can a Christian drink wine or beer?
            - **Points:**
              - Drunkenness is always sin.
            - **Passages:**
              - EPH 5:18: do not get drunk on wine
            """.trimIndent()
        )
        val router = TopicRouter.embedding(guides, FakeEmbeddingProvider())
        val fake = FakeEmbeddingProvider()
        assertEquals(listOf("alcohol"), router.route(fake.vector("Is drinking alcohol a sin?")).map { it.guide.id })
        assertEquals(emptyList(), router.route(fake.vector("Who was Melchizedek?")))
    }

    @Test
    fun theStoredVectorsRouteEveryPhraseToItsOwnGuide() {
        // Offline, with the real bge-m3 vectors: a phrase's own vector scores 1 on its guide.
        val router = TopicRouter.fromResources(VerseSearch.MODEL)
        val file = TopicVectors.load()
        val guides = TopicGuides.load().associateBy { it.id }
        for (phrase in file.phrases) {
            val picked = router.route(phrase.vector)
            val expected = guides.getValue(phrase.guideId)
            assertTrue(
                picked.first().guide == expected || picked.single().guide.alone,
                "${phrase.guideId}: \"${phrase.text}\" picked ${picked.map { it.guide.id }}"
            )
        }
    }
}
