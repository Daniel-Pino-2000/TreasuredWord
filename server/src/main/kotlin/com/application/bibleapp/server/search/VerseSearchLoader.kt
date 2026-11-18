package com.application.bibleapp.server.search

import com.application.bibleapp.server.ai.DevAiBudget
import com.application.bibleapp.server.search.eval.BibleText
import com.application.bibleapp.server.search.eval.DEFAULT_RETRY_PAUSES_MILLIS
import com.application.bibleapp.server.search.eval.EmbedProgress
import com.application.bibleapp.server.search.eval.Verse
import com.application.bibleapp.server.search.eval.VectorCache
import com.application.bibleapp.server.search.eval.VerseRef
import com.application.bibleapp.server.search.eval.embedMissingVerses
import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.system.exitProcess

/**
 * Fills the tables search by meaning ranks with (#287, phase 2, migration V9): the translation's
 * text into `search_verses` and one vector per verse into `verse_embeddings`.
 *
 * Vectors come from the evaluation's cache first ([cache], e.g. `bge-m3-bsb.vec`), so verses the
 * evaluation already paid for cost no neurons again; only verses missing from it are embedded,
 * and those are added to the cache as they arrive. Verses already in the database are skipped, so
 * a run stopped by the daily limit carries on the next day. It never creates tables: the server's
 * migration does that, and a loader that migrated Neon ahead of a deploy would leave the running
 * server unable to start (Flyway refuses a database with migrations it doesn't know).
 */
class VerseSearchLoader(
    private val connection: Connection,
    private val model: String,
    private val translation: String,
    private val cache: VectorCache,
    /** Only asked for when a verse isn't cached, so a run from the cache needs no token. */
    private val provider: () -> EmbeddingProvider,
    private val retryPausesMillis: List<Long> = DEFAULT_RETRY_PAUSES_MILLIS
) {
    class Result(
        val textsWritten: Int,
        val fromCache: Int,
        val embedding: EmbedProgress,
        val alreadyStored: Int
    ) {
        val stored: Int get() = fromCache + embedding.embedded
    }

    /** True when migration V9 has run on this database. */
    fun tablesExist(): Boolean = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT to_regclass('public.verse_embeddings') IS NOT NULL AND to_regclass('public.search_verses') IS NOT NULL"
        ).use { it.next() && it.getBoolean(1) }
    }

    /**
     * Loads [verses] (only the first [limit], when given, to try a few first). Writes every
     * verse's text (changed ones only), then stores the vectors the database doesn't have yet.
     */
    fun load(verses: List<Verse>, limit: Int? = null): Result {
        val chosen = verses.filter { it.text.isNotBlank() }.sortedBy { it.ref }.let { if (limit != null) it.take(limit) else it }
        val textsWritten = writeTexts(chosen)

        val stored = storedRefs()
        val missing = chosen.filter { it.ref !in stored }
        val vectors = cache.load()
        val cached = missing.filter { it.ref in vectors }
        insertVectors(cached.map { it.ref to vectors.getValue(it.ref) })

        val toEmbed = missing.filter { it.ref !in vectors }
        val progress = if (toEmbed.isEmpty()) {
            EmbedProgress(0, 0.0, false)
        } else {
            runBlocking { embedMissingVerses(toEmbed, vectors, cache, provider(), retryPausesMillis) }
        }
        // What was embedded before a stop is in the cache and the map, so store it either way.
        insertVectors(toEmbed.filter { it.ref in vectors }.map { it.ref to vectors.getValue(it.ref) })
        return Result(textsWritten, cached.size, progress, chosen.size - missing.size)
    }

    /** Inserts or updates the texts that changed; returns how many rows that was. */
    fun writeTexts(verses: List<Verse>): Int = inTransaction {
        connection.prepareStatement(
            """
            INSERT INTO search_verses (translation, book, chapter, verse, text) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (translation, book, chapter, verse) DO UPDATE SET text = EXCLUDED.text
            WHERE search_verses.text IS DISTINCT FROM EXCLUDED.text
            """.trimIndent()
        ).use { statement ->
            var written = 0
            for (batch in verses.chunked(BATCH)) {
                for (verse in batch) {
                    statement.setString(1, translation)
                    statement.setRef(2, verse.ref)
                    statement.setString(5, verse.text)
                    statement.addBatch()
                }
                written += statement.executeBatch().sumOf { it.coerceAtLeast(0) }
            }
            written
        }
    }

    fun storedRefs(): Set<VerseRef> =
        connection.prepareStatement("SELECT book, chapter, verse FROM verse_embeddings WHERE model = ? AND translation = ?").use { statement ->
            statement.setString(1, model)
            statement.setString(2, translation)
            statement.executeQuery().use { rows ->
                buildSet { while (rows.next()) add(VerseRef(rows.getInt(1), rows.getInt(2), rows.getInt(3))) }
            }
        }

    private fun insertVectors(entries: List<Pair<VerseRef, FloatArray>>) {
        if (entries.isEmpty()) return
        inTransaction {
            connection.prepareStatement(
                """
                INSERT INTO verse_embeddings (model, translation, book, chapter, verse, embedding)
                VALUES (?, ?, ?, ?, ?, ?::halfvec) ON CONFLICT DO NOTHING
                """.trimIndent()
            ).use { statement ->
                for (batch in entries.chunked(BATCH)) {
                    for ((ref, vector) in batch) {
                        statement.setString(1, model)
                        statement.setString(2, translation)
                        statement.setRef(3, ref)
                        statement.setString(6, vectorLiteral(vector))
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
        }
    }

    /** How big the tables are, in bytes, and the whole database (Neon's free plan has 1 GB). */
    fun sizes(): Map<String, Long> = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT pg_total_relation_size('verse_embeddings'), pg_total_relation_size('search_verses'), pg_database_size(current_database())"
        ).use { rows ->
            rows.next()
            mapOf("verse_embeddings" to rows.getLong(1), "search_verses" to rows.getLong(2), "database" to rows.getLong(3))
        }
    }

    private fun java.sql.PreparedStatement.setRef(first: Int, ref: VerseRef) {
        setShort(first, ref.book.toShort())
        setShort(first + 1, ref.chapter.toShort())
        setShort(first + 2, ref.verse.toShort())
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
        /** Rows per round trip: Neon is a few hundred ms away, and 200 vectors are ~1.6 MB of SQL. */
        private const val BATCH = 200

        /** pgvector's text form, `[0.1,-0.2,…]`, cast to halfvec by the insert. */
        fun vectorLiteral(vector: FloatArray): String = vector.joinToString(",", "[", "]")
    }
}

/** `jdbc:postgresql://host:5432/db?sslmode=require` → `host:5432/db`: says where, never who. */
internal fun describeDatabase(jdbcUrl: String): String =
    jdbcUrl.removePrefix("jdbc:postgresql://").substringBefore('?')

private fun megabytes(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)

/**
 * `./gradlew :server:loadVerseSearch -Pbible=<file.tsv>` (#287, phase 2). Arguments, from
 * build.gradle.kts: the translation TSV, the evaluation's cache folder, the model, and a verse
 * limit (empty: all). Connects with DATABASE_URL, DATABASE_USER and DATABASE_PASSWORD like the
 * server (default: the local Docker Postgres); AI_BASE_URL and AI_API_TOKEN are needed only for
 * verses the cache doesn't have. Prints counts, sizes and the database's host, never a password
 * or token.
 */
fun main(args: Array<String>) {
    require(args.size == 4) { "Usage: VerseSearchLoader <translation.tsv> <cache folder> <model> <limit>" }
    val (biblePath, cacheFolder, model, limitArg) = args
    if (biblePath.isBlank()) {
        System.err.println("Say which translation to load: -Pbible=<file.tsv> (docs/semantic_search_eval.md, \"Another translation\").")
        exitProcess(1)
    }
    val bible = File(biblePath)
    val translation = VectorCache.slug(bible.nameWithoutExtension)
    val verses = BibleText.readTsv(bible)
    val limit = limitArg.takeIf { it.isNotBlank() }?.toInt()

    val jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5433/bibleapp"
    val user = System.getenv("DATABASE_USER") ?: "postgres"
    val password = System.getenv("DATABASE_PASSWORD") ?: "postgres"
    val provider by lazy {
        val baseUrl = System.getenv("AI_BASE_URL")?.takeIf { it.isNotBlank() }
        val token = System.getenv("AI_API_TOKEN")?.takeIf { it.isNotBlank() }
        if (baseUrl == null || token == null) {
            System.err.println("Some verses aren't in the cache: set AI_BASE_URL and AI_API_TOKEN to embed them.")
            exitProcess(1)
        }
        // Loading the vectors reached Cloudflare's rate limit on 2026-11-16 and the app's questions
        // failed (#343): paced and counted like every development tool.
        DevAiBudget.fromEnvironment().also { println(it.summary()) }
            .wrap(WorkersAiEmbeddingProvider(WorkersAiEmbeddingProvider.accountAiUrl(baseUrl), token, model))
    }
    val cache = VectorCache(File(cacheFolder, VectorCache.fileName(model, translation)), model, translation)

    println("Loading $model vectors of $translation (${"%,d".format(limit ?: verses.size)} verses) into ${describeDatabase(jdbcUrl)}; cache: ${cache.file.path}")
    DriverManager.getConnection(jdbcUrl, user, password).use { connection ->
        val loader = VerseSearchLoader(connection, model, translation, cache, { provider })
        if (!loader.tablesExist()) {
            System.err.println("verse_embeddings or search_verses is missing: the server's migration V9 creates them. Deploy (or start the server locally) first.")
            exitProcess(1)
        }
        val before = loader.sizes()
        val result = loader.load(verses, limit)
        val after = loader.sizes()

        println("Texts written: ${"%,d".format(result.textsWritten)}")
        println(
            "Vectors stored: ${"%,d".format(result.stored)} (${"%,d".format(result.fromCache)} from the cache, " +
                "${"%,d".format(result.embedding.embedded)} embedded for ${"%.2f".format(result.embedding.neurons)}" +
                "${if (result.embedding.neuronsEstimated) " estimated" else ""} neurons); ${"%,d".format(result.alreadyStored)} were already there."
        )
        for (name in listOf("verse_embeddings", "search_verses", "database")) {
            println("  $name: ${megabytes(before.getValue(name))} -> ${megabytes(after.getValue(name))}")
        }
        if (!result.embedding.finished) {
            println("Stopped at ${result.embedding.stoppedAt}: ${result.embedding.stopReason}.")
            println(if (result.embedding.quotaExhausted) "Run the same command after 00:00 UTC to carry on." else "Run the same command again to carry on.")
            exitProcess(if (result.embedding.quotaExhausted) 0 else 1)
        }
    }
    exitProcess(0)
}
