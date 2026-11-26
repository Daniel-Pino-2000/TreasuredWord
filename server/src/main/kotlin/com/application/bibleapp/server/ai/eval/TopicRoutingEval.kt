package com.application.bibleapp.server.ai.eval

import com.application.bibleapp.server.ai.DevAiBudget
import com.application.bibleapp.server.ai.TopicGuides
import com.application.bibleapp.server.ai.TopicRouter
import com.application.bibleapp.server.search.EmbeddingKind
import com.application.bibleapp.server.search.VerseSearch
import com.application.bibleapp.server.search.WorkersAiEmbeddingProvider
import com.application.bibleapp.server.search.eval.QueryVectorCache
import com.application.bibleapp.server.search.eval.SearchEvalQueries
import com.application.bibleapp.server.search.eval.VerseRef
import com.application.bibleapp.server.search.eval.passageCovers
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.DriverManager
import kotlin.system.exitProcess

/**
 * The guide a person would pick for each question and search query, written before the routing
 * was run (2026-11-16), so the routing is graded against it. A set means any of them is right;
 * an empty set means no guide fits and none should be sent. Questions missing here expect none.
 */
private val expectedGuides: Map<String, Set<String>> = mapOf(
    // docs/ai_helper_eval.md
    "CORE-04" to setOf("jesus", "cross"), "CORE-05" to setOf("cross"), "CORE-06" to setOf("salvation"),
    "CORE-07" to setOf("security"), "CORE-09" to setOf("hell"), "CORE-10" to setOf("return", "endtimes"),
    "BAP-01" to setOf("baptism"), "BAP-02" to setOf("baptism"), "BAP-03" to setOf("baptism", "salvation"),
    "BAP-05" to setOf("women-pastors"), "BAP-06" to setOf("church"), "BAP-07" to setOf("government"),
    "DIS-02" to setOf("endtimes"), "DIS-05" to setOf("translations"), "DIS-06" to setOf("alcohol"),
    "TRAD-05" to setOf("islam"), "LIFE-01" to setOf("grief"), "LIFE-02" to setOf("doubt"),
    "LIFE-03" to setOf("repeated-sin"), "LIFE-04" to setOf("marriage"), "LIFE-05" to setOf("bible-reading"),
    "LIFE-06" to setOf("same-sex"), "CRISIS-02" to setOf("abuse", "crisis"),
    "CRISIS-05" to setOf("judas-peter"), "TRICK-05" to setOf("return"), "TRICK-07" to setOf("government"),
    // The crisis and medicine guides came after the pilot's first routing run (#319).
    "CRISIS-01" to setOf("crisis", "despair"), "CRISIS-03" to setOf("crisis", "despair", "failure"),
    "CRISIS-04" to setOf("crisis"), "TRICK-08" to setOf("medicine", "despair", "anxiety"),
    "LANG-02" to setOf("loneliness", "prayer"), "LANG-03" to setOf("baptism"), "LANG-04" to setOf("prayer"),
    "LANG-06" to setOf("anxiety"), "LANG-08" to setOf("salvation"), "LANG-09" to setOf("security"),
    // docs/ai_helper_eval_heldout.md
    "HO-01" to setOf("baptism"), "HO-02" to setOf("baptism"), "HO-03" to setOf("endtimes"),
    "HO-04" to setOf("translations"), "HO-05" to setOf("alcohol"), "HO-06" to setOf("grief"),
    "HO-07" to setOf("repeated-sin", "temptation"), "HO-08" to setOf("marriage"), "HO-09" to setOf("bible-reading"),
    "HO-10" to setOf("prayer"), "HO-11" to setOf("judas-peter"), "HO-12" to setOf("government"),
    "HO-13" to setOf("security"), "HO-14" to setOf("anxiety"), "HO-15" to setOf("islam"),
    "HO-16" to setOf("cross", "jesus"), "HO-17" to setOf("abuse", "crisis"), "HO-18" to setOf("women-pastors"),
    // docs/semantic_search_eval.md
    "TOPIC-01" to setOf("anxiety"), "TOPIC-02" to setOf("forgiving"), "TOPIC-03" to setOf("enemies"),
    "TOPIC-04" to setOf("salvation"), "TOPIC-05" to setOf("gossip"), "TOPIC-06" to setOf("temptation"),
    "TOPIC-07" to setOf("heaven"), "TOPIC-08" to setOf("giving"),
    "MOD-01" to setOf("anxiety"), "MOD-02" to setOf("rest"), "MOD-03" to setOf("god-far"),
    "MOD-04" to setOf("god-love"), "MOD-05" to setOf("forgiving"), "MOD-06" to setOf("comparison"),
    "MOD-07" to setOf("failure", "repeated-sin"), "MOD-08" to setOf("work"), "MOD-09" to setOf("illness"),
    "MOD-10" to setOf("loneliness"), "MOD-11" to setOf("despair"), "MOD-12" to setOf("guidance"),
    "LANG-DE-01" to setOf("anxiety", "fear"), "LANG-DE-02" to setOf("pride"), "LANG-ES-01" to setOf("forgiving"),
    "LANG-ES-02" to setOf("grief"), "LANG-FR-01" to setOf("loneliness"), "LANG-FR-02" to setOf("money"),
    "LANG-PT-01" to setOf("anxiety"), "LANG-PT-02" to setOf("prayer"), "LANG-ID-01" to setOf("enemies"),
    "LANG-ID-02" to setOf("salvation"), "LANG-FIL-01" to setOf("fear"), "LANG-FIL-02" to setOf("waiting"),
    "LANG-SW-01" to setOf("grief"), "LANG-SW-02" to setOf("work"), "LANG-KO-01" to setOf("creation"),
    "LANG-KO-02" to setOf("marriage"), "LANG-ZH-01" to setOf("anger"), "LANG-ZH-02" to setOf("parents")
)

private val thresholds = listOf(0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75)

private class Item(val id: String, val text: String, val set: String, val expected: Set<VerseRef> = emptySet())


/**
 * `./gradlew :server:topicRoutingEval` (#319). Embeds every question and search query once
 * (~1 neuron; the phrases come from topic_vectors.tsv), then: how often the right guide is
 * picked at each threshold, and how many search queries find an expected verse in the top 10
 * with search alone and with the matching guide's passages shown first. An optional fifth file
 * (`-Pprobes=`, or probes.txt in the report folder) holds extra questions, one a line as
 * `expected guide (or none) | question`, to check a guide change for false matches before any
 * answer is paid for. Questions' vectors are cached under the report folder, so a rerun with no
 * new question needs no token; `topic-scores.tsv` there holds every guide's score for each.
 */
fun main(args: Array<String>) {
    require(args.size in 4..5) { "Usage: TopicRoutingEval <ai_helper_eval.md> <heldout.md> <semantic_search_eval.md> <report folder> [probes.txt]" }
    val items = EvalQuestions.parse(File(args[0]).readText()).map { Item(it.id, it.ask, "main") } +
        EvalQuestions.parse(File(args[1]).readText()).map { Item(it.id, it.ask, "heldout") } +
        SearchEvalQueries.parse(File(args[2]).readText()).map { Item(it.id, it.query, "search", it.expected) }
    val probeFile = args.getOrNull(4)?.takeIf { it.isNotBlank() }?.let(::File) ?: File(args[3], "probes.txt").takeIf { it.exists() }
    val probes = probeFile?.readLines().orEmpty()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapIndexed { index, line -> "PROBE-${index + 1}" to line.split(" | ", limit = 2).map { it.trim() } }
    val expected = expectedGuides + probes.associate { (id, parts) -> id to parts[0].split("/").map { it.trim() }.filter { it != "none" }.toSet() }
    val allItems = items + probes.map { (id, parts) -> Item(id, parts[1], "probe") }
    val guides = TopicGuides.load()
    val router = TopicRouter.fromResources(VerseSearch.MODEL, threshold = 0.0)

    // The questions' vectors are kept under the report folder by their text, so a rerun after a
    // routing or guide change embeds only new or reworded questions, and needs no token when
    // there are none (#408).
    val cache = QueryVectorCache(File(args[3], "cache/${QueryVectorCache.fileName(VerseSearch.MODEL).removeSuffix(".queries")}-routing.queries"), VerseSearch.MODEL)
    val vectors = cache.load()
    val missing = allItems.map { it.text }.distinct().filter { it !in vectors }
    if (missing.isNotEmpty()) {
        val config = MapApplicationConfig().apply {
            for ((setting, variable) in listOf("baseUrl" to "AI_BASE_URL", "apiToken" to "AI_API_TOKEN")) {
                System.getenv(variable)?.let { put("ai.$setting", it) }
            }
        }
        val workersAi = WorkersAiEmbeddingProvider.fromConfig(config, VerseSearch.MODEL)
        if (workersAi == null) {
            System.err.println("${missing.size} questions need vectors: set AI_BASE_URL and AI_API_TOKEN first.")
            exitProcess(1)
        }
        // The app shares this Cloudflare account (#343).
        val embeddings = DevAiBudget.fromEnvironment().also { println(it.summary()) }.wrap(workersAi)
        runBlocking {
            for (batch in missing.chunked(embeddings.maxBatch)) {
                embeddings.embed(batch, EmbeddingKind.QUERY).vectors.forEachIndexed { i, v -> vectors[batch[i]] = v }
            }
        }
        cache.save(vectors)
    }
    val raw = allItems.associate { it.id to vectors.getValue(it.text) }
    val scored = allItems.associate { it.id to router.scoresFor(raw.getValue(it.id)) }

    val out = StringBuilder()
    fun line(text: String = "") { println(text); out.appendLine(text) }
    line("# Topic routing, ${guides.size} guides, ${items.size} questions and queries")
    line()
    line("Right: the expected guide is first. Wrong: another guide is sent. Missed: one was expected, none sent. Quiet: none expected, none sent.")
    line()
    line("| Threshold | Set | Right | Wrong | Missed | Quiet |")
    line("|---|---|---|---|---|---|")
    for (threshold in thresholds) {
        val picker = TopicRouter.fromResources(VerseSearch.MODEL, threshold)
        for (set in listOf("main", "heldout", "search", "probe").filter { set -> allItems.any { it.set == set } }) {
            var right = 0; var wrong = 0; var missed = 0; var quiet = 0
            for (item in allItems.filter { it.set == set }) {
                val wanted = expected[item.id].orEmpty()
                val picked = picker.pick(scored.getValue(item.id))
                when {
                    picked.isEmpty() && wanted.isEmpty() -> quiet++
                    picked.isEmpty() -> missed++
                    picked.first().guide.id in wanted -> right++
                    else -> wrong++
                }
            }
            line("| %.2f | $set | $right | $wrong | $missed | $quiet |".format(threshold))
        }
    }

    // Search: does an expected verse reach the top 10, with search alone and with the guide first?
    val jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5433/bibleapp"
    DriverManager.getConnection(jdbcUrl, System.getenv("DATABASE_USER") ?: "postgres", System.getenv("DATABASE_PASSWORD") ?: "postgres").use { connection ->
        connection.isReadOnly = true
        val searchItems = items.filter { it.set == "search" }
        val ranked = searchItems.associate { item ->
            item.id to VerseSearch.rankVerses(connection, VerseSearch.MODEL, VerseSearch.TRANSLATION, raw.getValue(item.id), item.text, 10)
                .map { VerseRef(it.book, it.chapter, it.verse) }
        }
        line()
        line("| Threshold | Search alone | Guide first, then search | Groups (TOPIC, MOD, KJV, LANG) |")
        line("|---|---|---|---|")
        for (threshold in thresholds) {
            val picker = TopicRouter.fromResources(VerseSearch.MODEL, threshold)
            var alone = 0; var withGuide = 0
            val byGroup = mutableMapOf<String, Int>()
            for (item in searchItems) {
                val passages = picker.pick(scored.getValue(item.id)).flatMap { it.guide.passages.map { p -> p.first } }
                val searchHit = ranked.getValue(item.id).any { it in item.expected }
                val guideHit = passages.any { passage -> item.expected.any { passageCovers(passage, it) } }
                val combinedHit = guideHit || ranked.getValue(item.id).take((10 - passages.size).coerceAtLeast(0)).any { it in item.expected }
                if (searchHit) alone++
                if (combinedHit) { withGuide++; byGroup.merge(item.id.substringBefore('-'), 1, Int::plus) }
            }
            line("| %.2f | $alone of ${searchItems.size} | $withGuide of ${searchItems.size} | ${listOf("TOPIC", "MOD", "KJV", "LANG").joinToString(", ") { "${byGroup[it] ?: 0}" }} |".format(threshold))
        }
    }

    line()
    val live = TopicRouter.fromResources(VerseSearch.MODEL)
    line("| Id | Set | Expected | Sent at %.2f | Top 3 (score) |".format(TopicRouter.THRESHOLD))
    line("|---|---|---|---|---|")
    for (item in allItems) {
        val top = scored.getValue(item.id).take(3).joinToString(", ") { "${it.guide.id} %.3f".format(it.score) }
        val sent = live.pick(scored.getValue(item.id)).joinToString(", ") { it.guide.id }.ifEmpty { "–" }
        val label = if (item.set == "probe") "${item.id}: ${item.text}" else item.id
        line("| $label | ${item.set} | ${expected[item.id].orEmpty().joinToString("/").ifEmpty { "–" }} | $sent | $top |")
    }
    // Every guide's score for every question, to try a routing rule without embedding again.
    File(args[3]).apply { mkdirs() }.resolve("topic-scores.tsv").writeText(buildString {
        appendLine("id\tset\texpected\tquestion\tguide\tscore")
        for (item in allItems) for (routed in scored.getValue(item.id)) {
            appendLine("${item.id}\t${item.set}\t${expected[item.id].orEmpty().joinToString("/")}\t${item.text}\t${routed.guide.id}\t${"%.4f".format(routed.score)}")
        }
    })
    val file = File(args[3]).apply { mkdirs() }.resolve("topic-routing.md")
    file.writeText(out.toString())
    println("Report: ${file.absolutePath}")
    exitProcess(0)
}
