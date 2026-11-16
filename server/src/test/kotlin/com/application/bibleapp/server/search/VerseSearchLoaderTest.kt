package com.application.bibleapp.server.search

import com.application.bibleapp.server.search.eval.Verse
import com.application.bibleapp.server.search.eval.VectorCache
import com.application.bibleapp.server.search.eval.VerseRef
import org.flywaydb.core.Flyway
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The search tables (migration V9) and their loader (#287, phase 2), against the real test
 * Postgres, which needs pgvector (CI's `pgvector/pgvector:pg18`). Vectors come from a fake
 * provider and a temporary cache: no network, no neurons.
 */
class VerseSearchLoaderTest {

    // Same defaults and env overrides as application.conf: the local Docker Postgres on 5433,
    // unless DATABASE_URL/USER/PASSWORD are set (CI's service runs on 5432).
    private val url = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5433/bibleapp"
    private val user = System.getenv("DATABASE_USER") ?: "postgres"
    private val password = System.getenv("DATABASE_PASSWORD") ?: "postgres"

    private lateinit var connection: Connection
    private val folder = Files.createTempDirectory("verse-search").toFile()
    // A translation name of its own, so this never touches real rows in a shared dev database.
    private val translation = "test-${UUID.randomUUID().toString().take(8)}"
    private val model = "@cf/test/fake-embedding"
    private val fake = FakeEmbeddingProvider(maxBatch = 2, dimensions = 1024)

    private val verses = listOf(
        Verse(VerseRef(40, 6, 34), "Therefore do not worry about tomorrow, for tomorrow will worry about itself."),
        Verse(VerseRef(50, 4, 6), "Be anxious for nothing, but in everything, by prayer and petition, present your requests to God."),
        Verse(VerseRef(19, 23, 1), "The LORD is my shepherd; I shall not want."),
        Verse(VerseRef(43, 11, 35), "Jesus wept.")
    )

    @BeforeTest
    fun connect() {
        Flyway.configure().dataSource(url, user, password).load().migrate()
        connection = DriverManager.getConnection(url, user, password)
    }

    @AfterTest
    fun cleanUp() {
        connection.prepareStatement("DELETE FROM verse_embeddings WHERE translation = ?").use { it.setString(1, translation); it.executeUpdate() }
        connection.prepareStatement("DELETE FROM search_verses WHERE translation = ?").use { it.setString(1, translation); it.executeUpdate() }
        connection.close()
        folder.deleteRecursively()
    }

    private fun cache() = VectorCache(folder.resolve("cache.vec"), model, translation)

    private fun loader(cache: VectorCache = cache()) =
        VerseSearchLoader(connection, model, translation, cache, { fake }, retryPausesMillis = listOf(0, 0, 0))

    private fun count(table: String): Int =
        connection.prepareStatement("SELECT count(*) FROM $table WHERE translation = ?").use { statement ->
            statement.setString(1, translation)
            statement.executeQuery().use { it.next(); it.getInt(1) }
        }

    @Test
    fun theMigrationMadeBothTables() {
        assertTrue(loader().tablesExist())
    }

    @Test
    fun cachedVectorsAreStoredWithoutCallingWorkersAi() {
        cache().append(verses.map { it.ref to fake.vector(it.text) })

        val result = loader().load(verses)

        assertEquals(4, result.fromCache)
        assertEquals(0, result.embedding.embedded)
        assertTrue(fake.calls.isEmpty())
        assertEquals(4, count("verse_embeddings"))
        assertEquals(4, count("search_verses"))
    }

    @Test
    fun onlyVersesMissingFromTheCacheAreEmbeddedAndThenCached() {
        cache().append(verses.take(1).map { it.ref to fake.vector(it.text) })

        val result = loader().load(verses)

        assertEquals(1, result.fromCache)
        assertEquals(3, result.embedding.embedded)
        assertEquals(3, fake.calls.sumOf { it.texts.size })
        assertTrue(fake.calls.all { it.kind == EmbeddingKind.DOCUMENT })
        assertEquals(4, cache().load().size) // the next run finds them in the cache
    }

    @Test
    fun aSecondRunStoresNothingNewAndOnlyChangedTextsAreWritten() {
        loader().load(verses)
        val calls = fake.calls.size

        val again = loader().load(verses.mapIndexed { i, v -> if (i == 0) v.copy(text = v.text + " Today has enough trouble of its own.") else v })

        assertEquals(1, again.textsWritten)
        assertEquals(0, again.stored)
        assertEquals(4, again.alreadyStored)
        assertEquals(calls, fake.calls.size)
    }

    @Test
    fun aLimitLoadsOnlyTheFirstVerses() {
        val result = loader().load(verses, limit = 2)

        assertEquals(2, result.stored)
        assertEquals(2, count("verse_embeddings"))
        // Sorted by reference: Psalm 23:1 and Matthew 6:34 come before John and Philippians.
        assertEquals(setOf(VerseRef(19, 23, 1), VerseRef(40, 6, 34)), loader().storedRefs())
    }

    @Test
    fun theDailyLimitStopsTheRunAndTheNextOneCarriesOn() {
        fake.quotaAfterCalls = 1 // one batch of 2, then "used up"

        val first = loader().load(verses)
        assertTrue(first.embedding.quotaExhausted)
        assertEquals(2, first.stored)
        assertEquals(2, count("verse_embeddings"))

        fake.quotaAfterCalls = null
        val second = loader().load(verses)
        assertTrue(second.embedding.finished)
        assertFalse(second.embedding.quotaExhausted)
        assertEquals(2, second.stored)
        assertEquals(4, count("verse_embeddings"))
    }

    @Test
    fun storedVectorsAndWordsCanBeSearched() {
        loader().load(verses)

        // pgvector: the closest vector to "worry tomorrow" is Matthew 6:34.
        val closest = connection.prepareStatement(
            "SELECT book, chapter, verse FROM verse_embeddings WHERE model = ? AND translation = ? ORDER BY embedding <=> ?::halfvec LIMIT 1"
        ).use { statement ->
            statement.setString(1, model)
            statement.setString(2, translation)
            statement.setString(3, VerseSearchLoader.vectorLiteral(fake.vector("worry tomorrow")))
            statement.executeQuery().use { it.next(); VerseRef(it.getInt(1), it.getInt(2), it.getInt(3)) }
        }
        assertEquals(VerseRef(40, 6, 34), closest)

        // Full-text search stems: "worrying" finds the verse that says "worry".
        val matches = connection.prepareStatement(
            "SELECT book, chapter, verse FROM search_verses WHERE translation = ? AND words @@ plainto_tsquery('english', ?)"
        ).use { statement ->
            statement.setString(1, translation)
            statement.setString(2, "worrying")
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(VerseRef(rows.getInt(1), rows.getInt(2), rows.getInt(3))) } }
        }
        assertEquals(listOf(VerseRef(40, 6, 34)), matches)
    }

    @Test
    fun theDatabaseIsDescribedWithoutCredentials() {
        assertEquals("ep-example.neon.tech/neondb", describeDatabase("jdbc:postgresql://ep-example.neon.tech/neondb?sslmode=require&password=secret"))
        assertEquals("localhost:5433/bibleapp", describeDatabase("jdbc:postgresql://localhost:5433/bibleapp"))
    }
}
