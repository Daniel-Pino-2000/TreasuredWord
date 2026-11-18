package com.application.bibleapp.server.search

import com.application.bibleapp.server.ai.TopicGuide
import com.application.bibleapp.server.ai.TopicGuides
import com.application.bibleapp.server.ai.TopicRouter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Search's key passages (#320): which guides' passages a query vector gets, with no embedding of its own. */
class KeyPassagesTest {

    private fun guide(id: String, vararg passages: String, alone: Boolean = false) =
        TopicGuide(id, id, listOf("$id phrase"), listOf("A point."), passages.map { it to "note" }, alone)

    private val cross = guide("cross", "1CO 15:3-8", "ROM 5:8")
    private val jesus = guide("jesus", "JHN 1:1", "1CO 15:3-8")
    private val grief = guide("grief", "JHN 11:35", "PSA 34:18")
    private val sin = guide("repeated-sin", "1JN 1:9", "ROM 6:1-2")
    private val crisis = guide("crisis", "PSA 34:18", "MAT 11:28", alone = true)

    /** cross and jesus point almost the same way (0.99 apart); grief another way; sin and crisis a third. */
    private val keyPassages = KeyPassages(
        TopicRouter(
            listOf(
                cross to floatArrayOf(1f, 0f, 0f, 0f),
                jesus to floatArrayOf(0.99f, 0.141f, 0f, 0f),
                grief to floatArrayOf(0f, 0f, 1f, 0f),
                sin to floatArrayOf(0f, 0f, 0f, 1f),
                crisis to floatArrayOf(0f, 0f, 0.199f, 0.98f)
            )
        )
    )

    @Test
    fun aMatchingQueryGetsItsGuidesPassagesInOrder() {
        assertEquals(listOf("JHN 11:35", "PSA 34:18"), keyPassages.forQuery(floatArrayOf(0f, 0.1f, 3f, 0f)))
    }

    @Test
    fun aCloseSecondGuideAddsItsPassagesOnce() {
        assertEquals(listOf("1CO 15:3-8", "ROM 5:8", "JHN 1:1"), keyPassages.forQuery(floatArrayOf(5f, 0f, 0f, 0f)))
    }

    @Test
    fun aCrisisGuideAmongThePickedShowsAlone() {
        // repeated-sin first (1.0), crisis within 0.03 (0.98): only the crisis guide's passages.
        assertEquals(listOf("PSA 34:18", "MAT 11:28"), keyPassages.forQuery(floatArrayOf(0f, 0f, 0f, 1f)))
    }

    @Test
    fun belowTheThresholdThereAreNone() {
        // 0.60 to cross, 0.53 to grief, 0.51 to jesus: near, but none at 0.70.
        assertEquals(emptyList(), keyPassages.forQuery(floatArrayOf(0.6f, -0.6f, 0.53f, 0f)))
        assertEquals(emptyList(), keyPassages.forQuery(floatArrayOf(0f, 1f, 0f, 0f)))
    }

    @Test
    fun theRealGuidesMatchTheirOwnPhrases() = runBlocking {
        val fake = FakeEmbeddingProvider(maxBatch = 32, dimensions = 1024)
        val keyPassages = KeyPassages(TopicRouter.embedding(TopicGuides.load(), fake))
        val salvation = TopicGuides.load().single { it.id == "salvation" }
        assertEquals(salvation.passages.map { it.first }, keyPassages.forQuery(fake.vector("How can I be saved?")))
    }
}
