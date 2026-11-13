package com.application.bibleapp.server.search.eval

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Reading `docs/semantic_search_eval.md` and the app's KJV (#287). Offline: both are files in the repo. */
class SearchEvalQueriesTest {

    // :server:test runs in server/, so the repo root is one level up.
    private val queriesFile = File("../docs/semantic_search_eval.md")
    private val kjvFile = File("../app/src/main/assets/bible_default.db")

    @Test
    fun everyQueryInTheRealFileIsRead() {
        val markdown = queriesFile.readText()
        val queries = SearchEvalQueries.parse(markdown)

        val lines = markdown.lines().map { it.trimEnd() }
        val headings = lines.drop(lines.indexOf("## Queries")).count { it.startsWith("### ") }
        assertEquals(headings, queries.size)
        assertTrue(queries.size in 35..50, "about 40 queries, not ${queries.size}")
        assertEquals(setOf("TOPIC", "MOD", "KJV", "LANG"), queries.map { it.group }.toSet())
        // At least two queries in each of the app's nine other languages.
        val languages = queries.groupingBy { it.language }.eachCount()
        for (language in listOf("de", "es", "fr", "pt-BR", "id", "fil", "sw", "ko", "zh-Hans")) {
            assertTrue((languages[language] ?: 0) >= 2, "$language has ${languages[language]} queries")
        }
        val worry = queries.single { it.id == "TOPIC-01" }
        assertEquals("worry", worry.query)
        assertEquals("en", worry.language)
        assertTrue(VerseRef(50, 4, 6) in worry.expected && VerseRef(50, 4, 7) in worry.expected)
        // The example in "How a query is written" comes before "## Queries" and isn't one.
        assertEquals(1, queries.count { it.id == "TOPIC-01" })
    }

    @Test
    fun everyExpectedVerseIsInTheKjv() {
        val verses = BibleText.readKjv(kjvFile).map { it.ref }.toSet()
        val missing = SearchEvalQueries.parse(queriesFile.readText())
            .flatMap { query -> query.expected.filter { it !in verses }.map { "${query.id}: $it" } }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun theKjvIsReadWhole() {
        val verses = BibleText.readKjv(kjvFile)

        assertEquals(31_102, verses.size)
        assertEquals(Verse(VerseRef(1, 1, 1), "In the beginning God created the heaven and the earth."), verses.first())
        assertEquals(VerseRef(66, 22, 21), verses.last().ref)
        assertTrue(verses.all { it.text.isNotBlank() && it.text == it.text.trim() })
        // 1 John 2:23 marks an added word as [but]; the brackets go, the word stays.
        val firstJohn = verses.single { it.ref == VerseRef(62, 2, 23) }.text
        assertTrue("but he that" in firstJohn && '[' !in firstJohn, firstJohn)
    }

    @Test
    fun referencesAndRanges() {
        assertEquals(listOf(VerseRef(50, 4, 6)), VerseRef.parseRange("PHP 4:6"))
        assertEquals(listOf(VerseRef(19, 55, 12), VerseRef(19, 55, 13), VerseRef(19, 55, 14)), VerseRef.parseRange(" PSA 55:12-14 "))
        assertEquals(listOf(VerseRef(62, 4, 9)), VerseRef.parseRange("1JN 4:9"))
        assertEquals("1JN 4:9", VerseRef(62, 4, 9).toString())
        assertFailsWith<IllegalArgumentException> { VerseRef.parseRange("PHL 4:6") }
        assertFailsWith<IllegalArgumentException> { VerseRef.parseRange("Philippians 4:6") }
        assertFailsWith<IllegalArgumentException> { VerseRef.parseRange("PHP 4:7-6") }
    }

    @Test
    fun aQueryWithoutExpectedVersesOrWithATypoIsAnError() {
        val noExpect = "## Queries\n\n### T-01\n- **Query:** worry\n"
        assertFailsWith<IllegalArgumentException> { SearchEvalQueries.parse(noExpect) }
        val typo = "## Queries\n\n### T-01\n- **Query:** worry\n- **Expect:** PHP 4:6, MTT 6:34\n"
        assertFailsWith<IllegalArgumentException> { SearchEvalQueries.parse(typo) }
        val twice = "## Queries\n\n### T-01\n- **Query:** a\n- **Expect:** GEN 1:1\n### T-01\n- **Query:** b\n- **Expect:** GEN 1:2\n"
        assertFailsWith<IllegalArgumentException> { SearchEvalQueries.parse(twice) }
    }

    @Test
    fun aTsvTranslationIsRead() {
        val file = File.createTempFile("bsb", ".tsv").apply { deleteOnExit() }
        file.writeText("# a comment\nGEN\t1\t1\tIn the beginning God created the heavens and the earth.\n\nJHN\t11\t35\t  Jesus wept.  \n")

        assertEquals(
            listOf(
                Verse(VerseRef(1, 1, 1), "In the beginning God created the heavens and the earth."),
                Verse(VerseRef(43, 11, 35), "Jesus wept.")
            ),
            BibleText.readTsv(file)
        )
        file.writeText("GEN\t1\t1\tone\nGEN\t1\t1\ttwice\n")
        assertFailsWith<IllegalArgumentException> { BibleText.readTsv(file) }
    }
}
