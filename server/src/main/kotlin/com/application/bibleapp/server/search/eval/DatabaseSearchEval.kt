package com.application.bibleapp.server.search.eval

import com.application.bibleapp.server.search.VerseSearch
import com.application.bibleapp.server.search.describeDatabase
import java.io.File
import java.sql.DriverManager
import kotlin.system.exitProcess

/**
 * `./gradlew :server:semanticSearchEvalDb` (#287, phase 3): the evaluation's queries through the
 * search endpoint's own SQL ([VerseSearch.rankVerses]) in whatever DATABASE_URL points at, after
 * `loadVerseSearch`. Phase 1 measured the ranking in memory with its own BM25; this checks that
 * Postgres's full-text ranking does as well, and times each query. It reads only, and uses the
 * evaluation's cached query vectors, so it spends no neurons. Prints ids, counts and times, never
 * a query or a password.
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "Usage: DatabaseSearchEval <queries.md> <cache folder> <model>" }
    val (queriesPath, cacheFolder, model) = args
    val queries = SearchEvalQueries.parse(File(queriesPath).readText())
    val cached = QueryVectorCache(File(cacheFolder, QueryVectorCache.fileName(model)), model).load()
    val missing = queries.filter { it.query !in cached }
    if (missing.isNotEmpty()) {
        System.err.println("No cached vector for ${missing.joinToString(", ") { it.id }}: run :server:semanticSearchEval first.")
        exitProcess(1)
    }

    val jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5433/bibleapp"
    val user = System.getenv("DATABASE_USER") ?: "postgres"
    val password = System.getenv("DATABASE_PASSWORD") ?: "postgres"
    println("${queries.size} queries against ${describeDatabase(jdbcUrl)}, $model / ${VerseSearch.TRANSLATION}")

    DriverManager.getConnection(jdbcUrl, user, password).use { connection ->
        connection.isReadOnly = true
        val millis = mutableListOf<Long>()
        val results = queries.map { query ->
            val started = System.nanoTime()
            val hits = VerseSearch.rankVerses(connection, model, VerseSearch.TRANSLATION, cached.getValue(query.query), query.query, TOP_K)
            millis += (System.nanoTime() - started) / 1_000_000
            val rank = hits.indexOfFirst { VerseRef(it.book, it.chapter, it.verse) in query.expected }.takeIf { it >= 0 }?.plus(1)
            query to rank
        }
        val hits = results.count { it.second != null }
        val mrr = results.sumOf { (_, rank) -> rank?.let { 1.0 / it } ?: 0.0 } / results.size
        println("Hits in the top $TOP_K: $hits of ${results.size}; mean reciprocal rank %.2f".format(mrr))
        results.groupBy { it.first.group }.forEach { (group, rows) ->
            println("  $group: ${rows.count { it.second != null }} of ${rows.size}")
        }
        println("Missed: ${results.filter { it.second == null }.joinToString(", ") { it.first.id }.ifEmpty { "none" }}")
        val sorted = millis.drop(1).sorted() // the first query also opens the session
        if (sorted.isNotEmpty()) println("Time per query: median ${sorted[sorted.size / 2]} ms, slowest ${sorted.last()} ms")
    }
    exitProcess(0)
}
