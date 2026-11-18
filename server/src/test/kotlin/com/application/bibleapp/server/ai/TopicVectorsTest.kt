package com.application.bibleapp.server.ai

import com.application.bibleapp.server.search.VerseSearch
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The phrase vectors made at build time match `topics.md` (#319): changing a guide's "Asks" line
 * without running `./gradlew :server:embedTopicGuides` fails here, before the server would refuse
 * to start with AI_TOPIC_GUIDES on.
 */
class TopicVectorsTest {

    @Test
    fun everyPhraseInTopicsMdHasAVectorAndNoVectorIsLeftOver() {
        val (missing, stale) = TopicVectors.differences(TopicGuides.load(), TopicVectors.load())
        val fix = "topics.md's Asks lines changed: run ./gradlew :server:embedTopicGuides and commit ${TopicVectors.PATH}"
        assertEquals(emptyList(), missing, "$fix (phrases without a vector)")
        assertEquals(emptyList(), stale, "$fix (vectors of phrases no longer there)")
    }

    @Test
    fun theVectorsAreBgeM3sWithTheServersModelAndUnitLength() {
        val file = TopicVectors.load()
        assertEquals(VerseSearch.MODEL, file.model)
        for (phrase in file.phrases) {
            assertEquals(1024, phrase.vector.size, phrase.text)
            val norm = sqrt(phrase.vector.sumOf { (it * it).toDouble() })
            assertTrue(abs(norm - 1) < 1e-3, "${phrase.text}: length $norm")
        }
        // The server reads them without error.
        TopicRouter.fromResources(VerseSearch.MODEL)
    }

    @Test
    fun aChangedPhraseIsCaught() {
        val guides = TopicGuides.load()
        val file = TopicVectors.load()
        val renamed = guides.map { if (it.id == "alcohol") it.copy(asks = it.asks.drop(1) + "Is beer okay?") else it }
        val (missing, stale) = TopicVectors.differences(renamed, file)
        assertEquals(listOf("alcohol: Is beer okay?"), missing)
        assertEquals(listOf("alcohol: ${guides.single { it.id == "alcohol" }.asks.first()}"), stale)
    }

    @Test
    fun theFileFormatRoundTrips() {
        val phrases = listOf(TopicVectors.Phrase("a", "Is it okay?", floatArrayOf(0.6f, -0.8f, 0f)))
        val parsed = TopicVectors.parse(TopicVectors.format("@cf/baai/bge-m3", phrases))
        assertEquals("@cf/baai/bge-m3", parsed.model)
        assertEquals("Is it okay?", parsed.phrases.single().text)
        assertContentEquals(floatArrayOf(0.6f, -0.8f, 0f), parsed.phrases.single().vector)
        assertFailsWith<IllegalArgumentException> { TopicVectors.format("m", listOf(TopicVectors.Phrase("a", "tab\there", floatArrayOf(1f)))) }
    }
}
