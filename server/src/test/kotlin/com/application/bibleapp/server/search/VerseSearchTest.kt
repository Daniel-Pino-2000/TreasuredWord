package com.application.bibleapp.server.search

import com.application.bibleapp.server.ai.QuotaExhausted
import com.application.bibleapp.server.routes.MutableClock
import com.application.bibleapp.server.search.eval.Verse
import com.application.bibleapp.server.search.eval.VectorCache
import com.application.bibleapp.server.search.eval.VerseRef
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Search by meaning's ranking (#287, phase 3) against the real test Postgres (pgvector), with a
 * fake bag-of-words embedding: no network, no neurons. Rows go in under a translation of their
 * own, so a dev database's real BSB rows are never touched or counted.
 */
class VerseSearchTest {

    private val url = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5433/bibleapp"
    private val user = System.getenv("DATABASE_USER") ?: "postgres"
    private val password = System.getenv("DATABASE_PASSWORD") ?: "postgres"

    private lateinit var connection: Connection
    private val folder = Files.createTempDirectory("verse-search").toFile()
    private val translation = "test-${UUID.randomUUID().toString().take(8)}"
    private val fake = FakeEmbeddingProvider(maxBatch = 4, dimensions = 1024)

    private val verses = listOf(
        Verse(VerseRef(40, 6, 34), "Therefore do not worry about tomorrow, for tomorrow will worry about itself."),
        Verse(VerseRef(50, 4, 6), "Be anxious for nothing, but in everything, by prayer and petition, present your requests to God."),
        Verse(VerseRef(19, 23, 1), "The LORD is my shepherd; I shall not want."),
        Verse(VerseRef(43, 11, 35), "Jesus wept."),
        Verse(VerseRef(19, 34, 18), "The LORD is near to the brokenhearted; He saves the contrite in spirit.")
    )

    @BeforeTest
    fun setUp() {
        Flyway.configure().dataSource(url, user, password).load().migrate()
        Database.connect(url, driver = "org.postgresql.Driver", user = user, password = password)
        connection = DriverManager.getConnection(url, user, password)
        VerseSearchLoader(connection, fake.model, translation, VectorCache(folder.resolve("cache.vec"), fake.model, translation), { fake })
            .load(verses)
        fake.calls.clear()
    }

    @AfterTest
    fun cleanUp() {
        connection.prepareStatement("DELETE FROM verse_embeddings WHERE translation = ?").use { it.setString(1, translation); it.executeUpdate() }
        connection.prepareStatement("DELETE FROM search_verses WHERE translation = ?").use { it.setString(1, translation); it.executeUpdate() }
        connection.close()
        folder.deleteRecursively()
    }

    private fun search(clock: MutableClock = MutableClock(), cap: Int = VerseSearch.DAILY_CAP) =
        VerseSearch(fake, translation, clock, cap)

    @Test
    fun `the verse closest in both rankings comes first, with one query embedding`() = runBlocking {
        val hits = search().search("worry about tomorrow")
        assertEquals(VerseRef(40, 6, 34), hits.first().let { VerseRef(it.book, it.chapter, it.verse) })
        assertEquals(1, fake.calls.size)
        assertEquals(listOf("worry about tomorrow"), fake.calls.single().texts)
        assertEquals(EmbeddingKind.QUERY, fake.calls.single().kind)
        // Every stored verse is a vector candidate, best first.
        assertEquals(verses.size, hits.size)
        assertTrue(hits.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test
    fun `the keywords half ORs the words, so a verse with only some of them still rises`() = runBlocking {
        // "brokenhearted" is only in Psalm 34:18; the other words are in no verse.
        val hits = search().search("I feel brokenhearted and alone tonight")
        assertEquals(VerseRef(19, 34, 18), hits.first().let { VerseRef(it.book, it.chapter, it.verse) })
    }

    @Test
    fun `a query of only common words is ranked by the vectors alone`() = runBlocking {
        val hits = search().search("what is it")
        assertEquals(verses.size, hits.size)
    }

    @Test
    fun `at most the limit`() = runBlocking {
        assertEquals(2, search().search("the LORD", limit = 2).size)
    }

    @Test
    fun `past the daily cap every search is refused until the next UTC day`() = runBlocking {
        val clock = MutableClock()
        val search = search(clock, cap = 2)
        search.search("worry")
        search.search("worry")
        assertFailsWith<QuotaExhausted> { search.search("worry") }
        assertEquals(2, fake.calls.size, "a refused search doesn't call the AI")

        clock.now = clock.now.plus(Duration.ofDays(1))
        search.search("worry")
        assertEquals(3, fake.calls.size)
    }
}
