package com.application.bibleapp.server.search

import com.application.bibleapp.server.ai.DevAiBudget
import com.application.bibleapp.server.search.eval.NavesTopics
import com.application.bibleapp.server.search.eval.QueryVectorCache
import com.application.bibleapp.server.search.eval.TopicEntry
import com.application.bibleapp.server.search.eval.VectorCache
import com.application.bibleapp.server.search.eval.VerseRef
import com.application.bibleapp.server.search.eval.embedWithRetries
import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.system.exitProcess

/**
 * Fills the topical index search by meaning ranks with (#394, migration V11): Nave's entries with
 * their vectors into `topic_entries`, and each entry's verses into `topic_entry_verses`.
 *
 * A load replaces all of [source]'s rows in one transaction, so a search sees the old index or the
 * new one, never half of each, and loading twice changes nothing. Like [VerseSearchLoader] it never
 * creates tables (the server's migration does) and reads vectors from the evaluation's cache, so a
 * run after the evaluation costs no neurons.
 */
class TopicSearchLoader(
    private val connection: Connection,
    private val source: String,
    private val model: String
) {
    class Result(val entries: Int, val verses: Int, val skipped: Int)

    /** True when migration V11 has run on this database. */
    fun tablesExist(): Boolean = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT to_regclass('public.topic_entries') IS NOT NULL AND to_regclass('public.topic_entry_verses') IS NOT NULL"
        ).use { it.next() && it.getBoolean(1) }
    }

    /** [translation]'s verses in search_verses, by book and chapter: what whole chapters expand to. */
    fun chapters(translation: String): Map<Pair<Int, Int>, List<VerseRef>> =
        connection.prepareStatement("SELECT book, chapter, verse FROM search_verses WHERE translation = ? ORDER BY book, chapter, verse").use { statement ->
            statement.setString(1, translation)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(VerseRef(rows.getInt(1), rows.getInt(2), rows.getInt(3))) }
            }
        }.groupBy { it.book to it.chapter }

    /**
     * Replaces [source]'s index with [entries] (their position is their id). An entry without a
     * vector in [vectors] or without a verse in [chapters] is skipped.
     */
    fun load(entries: List<TopicEntry>, vectors: Map<String, FloatArray>, chapters: Map<Pair<Int, Int>, List<VerseRef>>): Result {
        val usable = entries.withIndex().mapNotNull { (id, entry) ->
            val vector = vectors[entry.text] ?: return@mapNotNull null
            val verses = entry.verses(chapters).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Triple(id, entry, vector) to verses
        }
        inTransaction {
            for (table in listOf("topic_entry_verses", "topic_entries")) {
                connection.prepareStatement("DELETE FROM $table WHERE source = ?").use { it.setString(1, source); it.executeUpdate() }
            }
            connection.prepareStatement(
                "INSERT INTO topic_entries (source, model, id, label, embedding) VALUES (?, ?, ?, ?, ?::halfvec)"
            ).use { statement ->
                for (batch in usable.chunked(VECTOR_BATCH)) {
                    for ((row, _) in batch) {
                        val (id, entry, vector) = row
                        statement.setString(1, source)
                        statement.setString(2, model)
                        statement.setInt(3, id)
                        statement.setString(4, entry.text)
                        statement.setString(5, VerseSearchLoader.vectorLiteral(vector))
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
            connection.prepareStatement(
                "INSERT INTO topic_entry_verses (source, id, book, chapter, verse) VALUES (?, ?, ?, ?, ?)"
            ).use { statement ->
                for (batch in usable.flatMap { (row, verses) -> verses.map { row.first to it } }.chunked(VERSE_BATCH)) {
                    for ((id, ref) in batch) {
                        statement.setString(1, source)
                        statement.setInt(2, id)
                        statement.setShort(3, ref.book.toShort())
                        statement.setShort(4, ref.chapter.toShort())
                        statement.setShort(5, ref.verse.toShort())
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
        }
        return Result(usable.size, usable.sumOf { it.second.size }, entries.size - usable.size)
    }

    /** How big the two tables and the whole database are, in bytes (Neon's free plan has 1 GB). */
    fun sizes(): Map<String, Long> = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT pg_total_relation_size('topic_entries'), pg_total_relation_size('topic_entry_verses'), pg_database_size(current_database())"
        ).use { rows ->
            rows.next()
            mapOf("topic_entries" to rows.getLong(1), "topic_entry_verses" to rows.getLong(2), "database" to rows.getLong(3))
        }
    }

    private fun <T> inTransaction(block: () -> T): T {
        val autoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            return block().also { connection.commit() }
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = autoCommit
        }
    }

    companion object {
        /** 200 vectors are ~2 MB of SQL, as in [VerseSearchLoader]. */
        private const val VECTOR_BATCH = 200
        private const val VERSE_BATCH = 2_000
    }
}

private fun megabytes(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)

/**
 * `./gradlew :server:loadTopicSearch -Pnaves=<NavesTopicalDictionary.csv>` (#394). Arguments, from
 * build.gradle.kts: the CSV, the evaluation's cache folder and the model. Vectors come from the
 * cache the evaluation fills (`<model>-naves.queries`); entries missing from it are embedded (needs
 * AI_BASE_URL and AI_API_TOKEN then, ~270 neurons for all of them). Connects with DATABASE_URL,
 * DATABASE_USER and DATABASE_PASSWORD like the server; prints counts, sizes and the host, never a
 * password or token. Against Neon it writes production data, so it's never run by CI.
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "Usage: TopicSearchLoader <naves.csv> <cache folder> <model>" }
    val (csvPath, cacheFolder, model) = args
    if (csvPath.isBlank()) {
        System.err.println("Say where Nave's CSV is: -Pnaves=<NavesTopicalDictionary.csv> (docs/semantic_search_eval_heldout.md).")
        exitProcess(1)
    }
    val entries = NavesTopics.load(File(csvPath))
    val cache = QueryVectorCache(File(cacheFolder, "${VectorCache.slug(model.substringAfterLast('/'))}-naves.queries"), model)
    val vectors = cache.load()
    val missing = entries.map { it.text }.distinct().filter { it !in vectors }
    if (missing.isNotEmpty()) {
        val baseUrl = System.getenv("AI_BASE_URL")?.takeIf { it.isNotBlank() }
        val token = System.getenv("AI_API_TOKEN")?.takeIf { it.isNotBlank() }
        if (baseUrl == null || token == null) {
            System.err.println("${"%,d".format(missing.size)} entries aren't in ${cache.file.path}: set AI_BASE_URL and AI_API_TOKEN to embed them.")
            exitProcess(1)
        }
        val provider = DevAiBudget.fromEnvironment().also { println(it.summary()) }
            .wrap(WorkersAiEmbeddingProvider(WorkersAiEmbeddingProvider.accountAiUrl(baseUrl), token, model))
        println("Embedding ${"%,d".format(missing.size)} entries...")
        runBlocking {
            for (batch in missing.chunked(provider.maxBatch)) {
                embedWithRetries(provider, batch, EmbeddingKind.DOCUMENT).vectors.forEachIndexed { i, v -> vectors[batch[i]] = v }
            }
        }
        cache.save(vectors)
    }

    val jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5433/bibleapp"
    val user = System.getenv("DATABASE_USER") ?: "postgres"
    val password = System.getenv("DATABASE_PASSWORD") ?: "postgres"
    println("Loading ${"%,d".format(entries.size)} Nave's entries ($model) into ${describeDatabase(jdbcUrl)}")
    DriverManager.getConnection(jdbcUrl, user, password).use { connection ->
        val loader = TopicSearchLoader(connection, VerseSearch.TOPIC_SOURCE, model)
        if (!loader.tablesExist()) {
            System.err.println("topic_entries or topic_entry_verses is missing: the server's migration V11 creates them. Deploy (or start the server locally) first.")
            exitProcess(1)
        }
        val chapters = loader.chapters(VerseSearch.TRANSLATION)
        if (chapters.isEmpty()) {
            System.err.println("search_verses has no ${VerseSearch.TRANSLATION} verses: run loadVerseSearch first.")
            exitProcess(1)
        }
        val before = loader.sizes()
        val result = loader.load(entries, vectors, chapters)
        val after = loader.sizes()
        println("Entries: ${"%,d".format(result.entries)} stored, ${"%,d".format(result.skipped)} skipped (no verse in ${VerseSearch.TRANSLATION}); verse rows: ${"%,d".format(result.verses)}")
        for (name in listOf("topic_entries", "topic_entry_verses", "database")) {
            println("  $name: ${megabytes(before.getValue(name))} -> ${megabytes(after.getValue(name))}")
        }
    }
    exitProcess(0)
}
