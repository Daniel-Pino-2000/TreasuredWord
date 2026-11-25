package com.application.bibleapp.server.search

import com.application.bibleapp.server.search.eval.TopicEntry
import com.application.bibleapp.server.search.eval.TopicPassage
import com.application.bibleapp.server.search.eval.VerseRef
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Loading the topical index (#394, migration V11) into the test Postgres. Rows go in under a
 * source of their own, so a dev database's real Nave's rows are never touched.
 */
class TopicSearchLoaderTest {

    private val url = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5433/bibleapp"
    private val user = System.getenv("DATABASE_USER") ?: "postgres"
    private val password = System.getenv("DATABASE_PASSWORD") ?: "postgres"
    private val source = "test-${UUID.randomUUID().toString().take(8)}"
    private lateinit var connection: Connection

    // Psalm 23 with three verses and John 11:35, as a translation's chapters.
    private val chapters = listOf(VerseRef(19, 23, 1), VerseRef(19, 23, 2), VerseRef(19, 23, 3), VerseRef(43, 11, 35))
        .groupBy { it.book to it.chapter }

    private fun entry(text: String, vararg passages: TopicPassage) = TopicEntry(text.uppercase(), text, passages.toList())

    @BeforeTest
    fun setUp() {
        Flyway.configure().dataSource(url, user, password).load().migrate()
        connection = DriverManager.getConnection(url, user, password)
    }

    @AfterTest
    fun cleanUp() {
        for (table in listOf("topic_entry_verses", "topic_entries")) {
            connection.prepareStatement("DELETE FROM $table WHERE source = ?").use { it.setString(1, source); it.executeUpdate() }
        }
        connection.close()
    }

    private fun count(sql: String): Int = connection.prepareStatement(sql).use { statement ->
        statement.setString(1, source)
        statement.executeQuery().use { it.next(); it.getInt(1) }
    }

    @Test
    fun `whole chapters expand, missing verses and vectorless entries are skipped, and a reload replaces everything`() {
        val loader = TopicSearchLoader(connection, source, "@cf/test/fake-embedding")
        val vector = FloatArray(1024) { if (it == 0) 1f else 0f }
        val entries = listOf(
            entry("Shepherd", TopicPassage(19, 23, null, null)),
            // Verse 99 isn't in the translation; 1 is.
            entry("Want", TopicPassage(19, 23, 1, 1), TopicPassage(19, 23, 99, 99)),
            entry("Nothing here", TopicPassage(1, 1, 1, 1)),
            entry("No vector", TopicPassage(43, 11, 35, 35))
        )
        val vectors = entries.filter { it.text != "No vector" }.associate { it.text to vector }

        val result = loader.load(entries, vectors, chapters)

        assertEquals(2, result.entries)
        assertEquals(2, result.skipped)
        assertEquals(3 + 1, result.verses)
        assertEquals(2, count("SELECT count(*) FROM topic_entries WHERE source = ?"))
        assertEquals(4, count("SELECT count(*) FROM topic_entry_verses WHERE source = ?"))

        loader.load(entries.take(1), vectors, chapters)
        assertEquals(1, count("SELECT count(*) FROM topic_entries WHERE source = ?"))
        assertEquals(3, count("SELECT count(*) FROM topic_entry_verses WHERE source = ?"))
    }
}
