package com.application.bibleapp.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The Memorize games' text handling (#363): words and phrases in English, Spanish, Korean and
 * Chinese, the gaps and fading order, and the typed answer check (accents, punctuation, typos).
 * The verses are public-domain (KJV, RV1909) or paraphrases written for the test.
 */
class MemoryGamesTest {

    private val john316 = "For God so loved the world, that he gave his only begotten Son, that whosoever believeth in him should not perish, but have everlasting life."
    private val spanish = "Porque de tal manera amó Dios al mundo, que ha dado á su Hijo unigénito."
    private val korean = "하나님이 세상을 이처럼 사랑하사 독생자를 주셨으니"
    private val chinese = "神爱世人，甚至将他的独生子赐给他们。"

    @Test
    fun `English words keep their punctuation apart from the word`() {
        val words = practiceWords(john316)
        assertEquals(25, words.size)
        assertEquals("world", words[5].core)
        assertEquals(",", words[5].trailing)
        assertEquals("world", words[5].key)
        assertEquals(john316, words.joinWords())
        assertTrue(words[5].endsPhrase)
    }

    @Test
    fun `quotes, dashes and apostrophes`() {
        val words = practiceWords("“Don't fear—only believe.” — Jesus")
        assertEquals(listOf("Don't", "fear", "only", "believe", "Jesus"), words.map { it.core })
        assertEquals("“", words[0].leading)
        assertEquals("dont", words[0].key)
        assertFalse(words[2].spaceBefore) // "fear—only"
        assertEquals("“Don't fear—only believe.” — Jesus", words.joinWords())
    }

    @Test
    fun `Spanish accents are kept for display but don't matter for answers`() {
        val words = practiceWords(spanish)
        assertEquals("amó", words[4].core)
        assertEquals("amo", words[4].key)
        assertEquals("á", words[11].core)
        assertEquals(spanish, words.joinWords())
    }

    @Test
    fun `Korean keeps whole words, split at its spaces`() {
        val words = practiceWords(korean)
        assertEquals(listOf("하나님이", "세상을", "이처럼", "사랑하사", "독생자를", "주셨으니"), words.map { it.core })
        assertFalse(words.any { it.isCharacter })
        assertEquals(korean, words.joinWords())
    }

    @Test
    fun `Chinese is split by character, with no spaces put back`() {
        val words = practiceWords(chinese)
        assertEquals(16, words.size)
        assertTrue(words.all { it.isCharacter })
        assertEquals("人", words[3].core)
        assertEquals("，", words[3].trailing)
        assertTrue(words[3].endsPhrase)
        assertEquals(chinese, words.joinWords())
    }

    @Test
    fun `about one word in four is a gap, the same for the same seed`() {
        val words = practiceWords(john316)
        val gaps = pickGaps(words, Random(5))
        assertEquals(6, gaps.size)
        assertEquals(gaps, gaps.sorted())
        assertEquals(gaps, pickGaps(words, Random(5)))
        val bank = wordBank(words, gaps, Random(6))
        assertTrue(bank.containsAll(gaps))
        assertEquals(gaps.size + 3, bank.size)
        // Decoys are never another gap's answer.
        val answerKeys = gaps.map { words[it].key }.toSet()
        bank.filter { it !in gaps }.forEach { assertFalse(words[it].key in answerKeys) }
    }

    @Test
    fun `a two-word verse still has a gap`() {
        assertEquals(1, pickGaps(practiceWords("Jesus wept."), Random(1)).size)
    }

    @Test
    fun `phrases break at commas, and a long sentence is cut again`() {
        val words = practiceWords(john316)
        val phrases = splitPhrases(words)
        assertEquals(0, phrases.first().first)
        assertEquals(words.lastIndex, phrases.last().last)
        phrases.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        assertTrue(phrases.size in 3..8)
        assertEquals("For God so loved the", words.subList(phrases[0].first, phrases[0].last + 1).joinWords())
        // A verse of a few words is ordered word by word.
        assertEquals(3, splitPhrases(practiceWords("Rejoice evermore always.")).size)
    }

    @Test
    fun `Chinese phrases break at its own punctuation`() {
        val words = practiceWords(chinese)
        val phrases = splitPhrases(words)
        assertTrue(phrases.size >= 3)
        assertEquals(3, phrases[0].last) // 神爱世人，
    }

    @Test
    fun `the shuffled order is never the right one`() {
        repeat(20) { seed -> assertNotEquals((0 until 4).toList(), shuffledOrder(4, Random(seed))) }
        assertEquals(listOf(0), shuffledOrder(1, Random(1)))
    }

    @Test
    fun `first letters, and Chinese shows each phrase's first character`() {
        val words = practiceWords("For God so loved the world,")
        assertEquals("F G s l t w,", words.indices.joinToString(" ") { firstLetterHint(words, it) })
        val zh = practiceWords(chinese)
        assertEquals("神", firstLetterHint(zh, 0))
        assertEquals("○", firstLetterHint(zh, 1))
        assertEquals("○，", firstLetterHint(zh, 3))
        assertEquals("甚", firstLetterHint(zh, 4))
    }

    @Test
    fun `words fade in four steps until none are left`() {
        val words = practiceWords(john316)
        val order = fadingOrder(words, Random(2))
        assertEquals(words.size, order.toSet().size)
        assertEquals(0, fadingHiddenCount(order.size, 0))
        assertEquals(7, fadingHiddenCount(order.size, 1))
        assertEquals(order.size, fadingHiddenCount(order.size, FADING_STEPS))
    }

    @Test
    fun `tapping games pass at three in four right the first time`() {
        assertTrue(tapGamePassed(3, 4))
        assertFalse(tapGamePassed(2, 4))
        assertTrue(tapGamePassed(2, 2))
        assertFalse(tapGamePassed(1, 2))
    }

    @Test
    fun `typing ignores case, punctuation, accents and extra spaces`() {
        val check = checkTypedVerse(practiceWords(spanish), "porque de tal  manera amo dios al mundo que ha dado a su hijo unigenito")
        assertTrue(check.marks.all { it == TypedMark.RIGHT })
        assertEquals(1.0, check.score, 0.0)
        assertTrue(check.passed)
    }

    @Test
    fun `one typo per ten words is forgiven`() {
        val words = practiceWords(john316) // 25 words: 3 typos forgiven
        val typed = "For God so lvoed the world that he gave his only begoten Son that whosoever beleiveth in him should not perish but have everlasting life"
        val check = checkTypedVerse(words, typed)
        assertEquals(3, check.marks.count { it == TypedMark.TYPO })
        assertEquals(1.0, check.score, 0.0)
        // A fourth typo is a wrong word, but 24 of 25 still passes.
        val fourTypos = checkTypedVerse(words, typed.replace("everlasting", "everlastign"))
        assertEquals(1, fourTypos.marks.count { it == TypedMark.WRONG })
        assertTrue(fourTypos.passed)
    }

    @Test
    fun `a missed word is marked missed without making the rest wrong`() {
        val words = practiceWords(john316)
        val check = checkTypedVerse(words, john316.replace(" only", ""))
        assertEquals(1, check.marks.count { it == TypedMark.MISSED })
        assertEquals(TypedMark.MISSED, check.marks[words.indexOfFirst { it.core == "only" }])
        assertEquals(24, check.right)
        assertTrue(check.passed) // 96%
    }

    @Test
    fun `under 90 percent fails, and extra words count against`() {
        val words = practiceWords(john316)
        val tooFew = checkTypedVerse(words, "For God so loved the world, that he gave his only begotten Son")
        assertFalse(tooFew.passed)
        val padded = checkTypedVerse(practiceWords("Jesus wept."), "and then Jesus wept a lot")
        assertEquals(4, padded.extraWords)
        assertFalse(padded.passed)
        assertFalse(checkTypedVerse(words, "").passed)
    }

    @Test
    fun `Korean and Chinese answers are checked word by word and character by character`() {
        assertTrue(checkTypedVerse(practiceWords(korean), "하나님이 세상을 이처럼 사랑하사 독생자를 주셨으니").passed)
        assertFalse(checkTypedVerse(practiceWords(korean), "하나님이 세상을").passed)
        val zh = checkTypedVerse(practiceWords(chinese), "神爱世人甚至将他的独生子赐给他们")
        assertEquals(1.0, zh.score, 0.0)
        // Traditional characters count as the Simplified ones (#96).
        assertTrue(checkTypedVerse(practiceWords(chinese), "神愛世人，甚至將他的獨生子賜給他們。").passed)
        // One wrong character of 16 still passes; two don't.
        assertTrue(checkTypedVerse(practiceWords(chinese), "神爱世人甚至将他的独生子赐给你们").passed)
        assertFalse(checkTypedVerse(practiceWords(chinese), "神爱世人甚至将你的独生子赐给你们").passed)
    }

    @Test
    fun `edit distance counts a swap of two letters as one`() {
        assertEquals(1, editDistance("loved", "lvoed"))
        assertEquals(1, editDistance("world", "word"))
        assertEquals(2, editDistance("abc", "cab"))
    }
}
