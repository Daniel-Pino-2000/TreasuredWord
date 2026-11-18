package com.application.bibleapp.server.ai

import com.application.bibleapp.server.search.FakeEmbeddingProvider
import com.application.bibleapp.server.search.VerseSearch
import com.application.bibleapp.server.warmUpEmbeddings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Matching a Bible Helper question to its guides never fails the question (#319). */
class TopicGuideMatcherTest {

    private val guides = TopicGuides.parse(
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
    private val fake = FakeEmbeddingProvider()
    private val router = runBlocking { TopicRouter.embedding(guides, FakeEmbeddingProvider()) }

    @Test
    fun aMatchingQuestionGetsItsGuide() = runBlocking {
        val matcher = TopicGuideMatcher(router, { fake.embed(listOf(it), com.application.bibleapp.server.search.EmbeddingKind.QUERY).vectors.single() })
        assertEquals(listOf("alcohol"), matcher.match("Is drinking alcohol a sin?").map { it.guide.id })
        assertEquals(emptyList(), matcher.match("Who was Melchizedek?"))
    }

    @Test
    fun aSlowEmbeddingIsGivenUpAndTheQuestionGetsNoGuide() = runBlocking {
        val matcher = TopicGuideMatcher(router, { delay(5_000); fake.vector(it) }, timeoutMillis = 50, firstTimeoutMillis = 50)
        val started = System.nanoTime()
        assertEquals(emptyList(), matcher.match("Is drinking alcohol a sin?"))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "it didn't wait for the embedding")
    }

    @Test
    fun anErrorOrNoNeuronsMeanNoGuide() = runBlocking {
        val matcher = TopicGuideMatcher(router, { throw Unavailable("down") })
        assertEquals(emptyList(), matcher.match("Is drinking alcohol a sin?"))
        val broken = TopicGuideMatcher(router, { error("a bug") })
        assertEquals(emptyList(), broken.match("Is drinking alcohol a sin?"))
    }

    @Test
    fun eachMatchCountsAgainstSearchsDailyCap() = runBlocking {
        val search = VerseSearch(fake, dailyCap = 1)
        val matcher = TopicGuideMatcher(router, search::embedQuery)
        assertEquals(listOf("alcohol"), matcher.match("Is drinking alcohol a sin?").map { it.guide.id })
        // The cap is used up: no embedding, no guide, and no error.
        val callsBefore = fake.calls.size
        assertEquals(emptyList(), matcher.match("Is drinking alcohol a sin?"))
        assertEquals(callsBefore, fake.calls.size)
    }

    @Test
    fun aStartersGuidesAreRememberedButAFailureIsTriedAgain() = runBlocking {
        var calls = 0
        var failing = true
        val matcher = TopicGuideMatcher(router, {
            calls++
            if (failing) throw QuotaExhausted("used up")
            fake.vector(it)
        })
        assertEquals(emptyList(), matcher.matchStarter("x/en", "Is drinking alcohol a sin?"))
        failing = false
        assertEquals(listOf("alcohol"), matcher.matchStarter("x/en", "Is drinking alcohol a sin?").map { it.guide.id })
        assertEquals(listOf("alcohol"), matcher.matchStarter("x/en", "Is drinking alcohol a sin?").map { it.guide.id })
        assertEquals(2, calls)
    }

    @Test
    fun onlyTheFirstMatchAfterAStartGetsTheLongerLimit() = runBlocking {
        // #341: the first embedding after a start pays for the connection; later ones don't wait as long.
        val matcher = TopicGuideMatcher(router, { delay(300); fake.vector(it) }, timeoutMillis = 100, firstTimeoutMillis = 5_000)
        assertEquals(listOf("alcohol"), matcher.match("Is drinking alcohol a sin?").map { it.guide.id })
        assertEquals(emptyList(), matcher.match("Is drinking alcohol a sin?"))
        assertEquals(emptyList(), matcher.match("Is drinking alcohol a sin?"))
    }

    @Test
    fun aFailedFirstMatchStillUsesUpTheLongerLimit() = runBlocking {
        var failing = true
        val matcher = TopicGuideMatcher(router, {
            if (failing) throw Unavailable("down")
            delay(300); fake.vector(it)
        }, timeoutMillis = 100, firstTimeoutMillis = 5_000)
        assertEquals(emptyList(), matcher.match("Is drinking alcohol a sin?"))
        failing = false
        assertEquals(emptyList(), matcher.match("Is drinking alcohol a sin?"))
    }

    @Test
    fun theWarmUpEmbedsOnceAndNeverThrows() = runBlocking {
        val texts = mutableListOf<String>()
        TopicGuideMatcher(router, { texts += it; fake.vector(it) }).warmUp()
        assertEquals(1, texts.size)
        TopicGuideMatcher(router, { throw Unavailable("down") }).warmUp()
        TopicGuideMatcher(router, { error("a bug") }).warmUp()
    }

    @Test
    fun theWarmUpDoesntUseUpTheFirstMatchsLongerLimit() = runBlocking {
        val matcher = TopicGuideMatcher(router, { delay(300); fake.vector(it) }, timeoutMillis = 100, firstTimeoutMillis = 5_000)
        matcher.warmUp()
        assertEquals(listOf("alcohol"), matcher.match("Is drinking alcohol a sin?").map { it.guide.id })
    }

    @Test
    fun withTheGuidesOnTheStartupWarmUpIsOneEmbeddingAndOffItsTheFreeOne() = runBlocking {
        var embeddings = 0
        var freeWarmUps = 0
        val matcher = TopicGuideMatcher(router, { embeddings++; fake.vector(it) })
        warmUpEmbeddings(matcher) { freeWarmUps++ }
        assertEquals(1, embeddings)
        assertEquals(0, freeWarmUps)
        // Guides off: no embedding is spent, only the free model-list request (#347).
        warmUpEmbeddings(null) { freeWarmUps++ }
        assertEquals(1, embeddings)
        assertEquals(1, freeWarmUps)
    }
}
