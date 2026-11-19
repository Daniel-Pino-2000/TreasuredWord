package com.application.bibleapp.server.ai

import com.application.bibleapp.server.search.FakeEmbeddingProvider
import com.application.bibleapp.server.search.VerseSearch
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Sending each Bible Helper question only the doctrine items it needs (#297). */
class DoctrineTest {

    private val small = Doctrine.parse(
        """
        # What the Bible Helper believes

        A summary.

        1. **Scripture.** God inspired the Bible.
        2. **God.** One God.
           - **The Son** is fully God.
        3. **Humanity.** Made in His image.
        4. **Salvation.** By grace through faith.
        5. **Baptism.** Immersion in water after faith. It does not save.
        6. **The church.** Pastors and deacons lead it. The office of pastor is limited to men.
        7. **Missions.** Make disciples of all nations.
        8. **The family.** Marriage is one man and one woman for life.
        9. **Stewardship.** Give generously and gladly.
        10. **Last things.** Jesus will return and judge
            everyone.
        """.trimIndent()
    )

    @Test
    fun theRealDoctrineSplitsIntoItsItemsAndJoinsBackExactly() {
        val doctrine = SystemPrompt.doctrine
        assertEquals((1..18).toList(), doctrine.numbers)
        assertEquals("Scripture", doctrine.items.first().title)
        assertEquals("The family", doctrine.items.last().title)
        // The whole doctrine is the file as v5 and v16 send it, byte for byte.
        assertEquals(SystemPrompt.load("ai/doctrine.md"), doctrine.text())
        assertTrue(doctrine.header.startsWith("# What the Bible Helper believes"))
    }

    @Test
    fun aSelectionKeepsTheHeaderSaysItsASelectionAndKeepsTheNumbers() {
        val text = small.text(listOf(1, 2, 3, 4, 6))
        assertTrue(text.startsWith("# What the Bible Helper believes\n\nA summary. ${Doctrine.PARTIAL_NOTE}\n\n"))
        assertTrue("6. **The church.**" in text)
        assertTrue("   - **The Son** is fully God." in text)
        assertFalse("5. **Baptism.**" in text || "Marriage" in text || "10. **Last things.**" in text)
        // The whole doctrine has no such line.
        assertFalse(Doctrine.PARTIAL_NOTE in small.text())
    }

    @Test
    fun anItemsChunksAreTheWholeItemAndEachSentenceUnderItsTitle() {
        val church = small.items.single { it.number == 6 }
        assertEquals(
            listOf(
                "The church. Pastors and deacons lead it. The office of pastor is limited to men.",
                "The church. Pastors and deacons lead it.",
                "The church. The office of pastor is limited to men."
            ),
            church.chunks()
        )
        // A wrapped line and a sub-item become one plain line, with no Markdown.
        assertEquals("Last things. Jesus will return and judge everyone.", small.items.last().chunks().first())
        assertEquals("God. One God. The Son is fully God.", small.items[1].chunks().first())
        for (item in SystemPrompt.doctrine.items) {
            for (chunk in item.chunks()) assertFalse('\t' in chunk || '\n' in chunk || "**" in chunk, chunk)
        }
    }

    @Test
    fun aBrokenDoctrineIsRefused() {
        assertFailsWith<IllegalArgumentException> { Doctrine.parse("# Title\n\nNo items.") }
        assertFailsWith<IllegalArgumentException> { Doctrine.parse("# Title\n\n1. **A.** a\n3. **C.** c") }
        assertFailsWith<IllegalArgumentException> { Doctrine.parse("# Title\n\n1. **A.** a\nnot indented") }
    }

    @Test
    fun aQuestionGetsItemsOneToFourAndItsClosestOthers() = runBlocking {
        val router = DoctrineRouter.embedding(small, FakeEmbeddingProvider(dimensions = 512), extra = 2)
        val fake = FakeEmbeddingProvider(dimensions = 512)
        val picked = router.pick(fake.vector("Can women be a pastor of a church?"))
        assertEquals(6, picked.size)
        assertTrue(picked.containsAll(DoctrineRouter.ALWAYS))
        assertTrue(6 in picked, "$picked")
        assertEquals(picked.sorted(), picked)
        assertTrue(8 in router.pick(fake.vector("Is marriage for life, one man and one woman?")))
        assertTrue(5 in router.pick(fake.vector("Does baptism in water save?")))
        // Items 1-4 are never scored, since they always go.
        assertTrue(router.scoresFor(fake.vector("God")).none { it.first in DoctrineRouter.ALWAYS })
    }

    @Test
    fun theStoredVectorsMatchDoctrineMdAndTheServersModel() {
        val file = TopicVectors.load(DoctrineRouter.PATH)
        val (missing, stale) = DoctrineRouter.differences(SystemPrompt.doctrine, file)
        val fix = "doctrine.md changed: run ./gradlew :server:embedDoctrine and commit ${DoctrineRouter.PATH}"
        assertEquals(emptyList(), missing, "$fix (chunks without a vector)")
        assertEquals(emptyList(), stale, "$fix (vectors of chunks no longer there)")
        assertEquals(VerseSearch.MODEL, file.model)
        for (phrase in file.phrases) {
            assertEquals(1024, phrase.vector.size, phrase.text)
            val norm = sqrt(phrase.vector.sumOf { (it * it).toDouble() })
            assertTrue(abs(norm - 1) < 1e-3, "${phrase.text}: length $norm")
        }
        // The server reads them without error, and a changed item is caught.
        DoctrineRouter.fromResources(VerseSearch.MODEL)
        val changed = Doctrine(SystemPrompt.doctrine.header, SystemPrompt.doctrine.items.map { if (it.number == 13) it.copy(text = it.text + " More.") else it })
        assertTrue(DoctrineRouter.differences(changed, file).first.isNotEmpty())
        assertFailsWith<IllegalArgumentException> { DoctrineRouter.fromResources(VerseSearch.MODEL, changed) }
        assertFailsWith<IllegalArgumentException> { DoctrineRouter.fromResources("@cf/other/model") }
    }

    @Test
    fun theInstructionsHoldOnlyThePickedItemsAndSayWhichVersion() {
        val whole = SystemPrompt.forLanguage("en")
        assertEquals(whole, SystemPrompt.withGuides("en", emptyList()))
        assertEquals(whole, SystemPrompt.withGuides("en", emptyList(), doctrineItems = null))
        val some = SystemPrompt.withGuides("es", emptyList(), listOf(1, 2, 3, 4, 7, 15))
        assertTrue("Always answer in Spanish" in some)
        assertTrue(Doctrine.PARTIAL_NOTE in some && "7. **Baptism and the Lord's Supper.**" in some && "15. **The Christian and society.**" in some)
        assertFalse("6. **The church.**" in some || "18. **The family.**" in some)
        assertFalse(Regex("\\{[a-zA-Z]+\\}").containsMatchIn(some))
        assertTrue(some.length < whole.length)
        // Everything after the doctrine is unchanged.
        assertEquals(whole.substringAfter("# 3. Where Baptists differ"), SystemPrompt.forLanguage("en", doctrineItems = listOf(1)).substringAfter("# 3. Where Baptists differ"))

        assertEquals(SystemPrompt.PROMPT_VERSION, SystemPrompt.versionFor(QuestionMatch.NONE))
        assertEquals(SystemPrompt.DOCTRINE_VERSION, SystemPrompt.versionFor(QuestionMatch(emptyList(), listOf(1, 2, 3, 4))))
        assertEquals(whole, SystemPrompt.withMatch("en", QuestionMatch.NONE))
    }
}
