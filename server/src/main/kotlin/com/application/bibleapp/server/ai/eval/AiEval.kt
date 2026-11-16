package com.application.bibleapp.server.ai.eval

import com.application.bibleapp.server.ai.AiMessage
import com.application.bibleapp.server.ai.AiProvider
import com.application.bibleapp.server.ai.AiRole
import com.application.bibleapp.server.ai.HelperVerses
import com.application.bibleapp.server.ai.OpenAiCompatibleProvider
import com.application.bibleapp.server.ai.QuotaExhausted
import com.application.bibleapp.server.ai.SystemPrompt
import com.application.bibleapp.server.ai.Unavailable
import com.application.bibleapp.server.routes.AI_MAX_TOKENS
import com.application.bibleapp.server.search.FoundVerse
import com.application.bibleapp.server.search.VerseSearch
import com.application.bibleapp.server.search.WorkersAiEmbeddingProvider
import com.application.bibleapp.server.search.describeDatabase
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.system.exitProcess

/** One question's outcome: the answer and the automatic checks' problems, or why there's no answer. */
data class EvalResult(
    val question: EvalQuestion,
    val answer: String? = null,
    val cutOff: Boolean = false,
    val neurons: Double? = null,
    val problems: List<String> = emptyList(),
    val error: String? = null,
    /** The verses search found and the instructions included (#287, phase 5); null when the run doesn't search. */
    val verses: List<FoundVerse>? = null
) {
    val passed: Boolean get() = error == null && problems.isEmpty()
}

/**
 * Sends each question through the same instructions, token limit and provider as the live route
 * (#126), one at a time. Stops at Cloudflare's daily limit: the rest would fail the same way, and
 * the report says so. With [verses] (the live route's [HelperVerses], #287 phase 5), each question
 * first gets the verses search finds for it, as the route does with AI_HELPER_VERSES on.
 */
suspend fun evaluate(questions: List<EvalQuestion>, provider: AiProvider, verses: HelperVerses? = null): List<EvalResult> {
    val results = mutableListOf<EvalResult>()
    for (question in questions) {
        val found = verses?.find(question.ask)
        val messages = listOf(
            AiMessage(AiRole.SYSTEM, SystemPrompt.withVerses(question.language, found.orEmpty())),
            AiMessage(AiRole.USER, question.ask)
        )
        val result = try {
            val completion = provider.complete(messages, AI_MAX_TOKENS)
            val problems = EvalChecks.check(question, completion.text).toMutableList()
            if (completion.cutOff) problems += "cut off: ran out of tokens"
            EvalResult(question, completion.text, completion.cutOff, completion.neurons, problems, verses = found)
        } catch (e: QuotaExhausted) {
            results += EvalResult(question, error = "Cloudflare's free daily allowance ran out; stopped here")
            break
        } catch (e: Unavailable) {
            EvalResult(question, error = "No answer: ${e.message}")
        }
        results += result
        println("${question.id}: ${if (result.passed) "ok" else result.error ?: result.problems.joinToString("; ")}")
    }
    return results
}

/** The report people read and grade: a summary table, then each question with its answer. */
fun report(results: List<EvalResult>, model: String, date: LocalDate, total: Int, promptVersion: String = SystemPrompt.PROMPT_VERSION): String = buildString {
    val answered = results.count { it.answer != null }
    val neurons = results.mapNotNull { it.neurons }.sum()
    appendLine("# Bible Helper evaluation, $date")
    appendLine()
    appendLine("- **Model:** `$model`")
    appendLine("- **Instructions:** version $promptVersion")
    if (results.any { it.verses != null }) {
        val withVerses = results.count { !it.verses.isNullOrEmpty() }
        appendLine("- **Verses from search:** $withVerses of ${results.size} questions (the others got version ${SystemPrompt.PROMPT_VERSION}'s instructions alone)")
    }
    appendLine("- **Answered:** $answered of $total questions")
    appendLine("- **Automatic checks passed:** ${results.count { it.passed }} of ${results.size}")
    appendLine("- **Neurons used:** ${"%.0f".format(neurons)} of the free 10,000 a day")
    appendLine()
    appendLine("Grade each answer with the rubric in `docs/ai_helper_eval.md` (Good, Needs work or Wrong) and summarize in the PR.")
    appendLine()
    appendLine("| Question | Language | Checks | Neurons |")
    appendLine("|---|---|---|---|")
    for (result in results) {
        val checks = when {
            result.error != null -> "no answer"
            result.problems.isEmpty() -> "ok"
            else -> result.problems.joinToString("; ").replace("|", "\\|")
        }
        appendLine("| ${result.question.id} | ${result.question.language} | $checks | ${result.neurons?.let { "%.1f".format(it) } ?: "–"} |")
    }
    for (result in results) {
        val question = result.question
        appendLine()
        appendLine("## ${question.id}")
        appendLine()
        appendLine("- **Ask:** ${question.ask}")
        appendLine("- **Language:** ${question.language} · **Crisis:** ${question.crisis.name.lowercase()}")
        question.must?.let { appendLine("- **Must:** $it") }
        question.mustNot?.let { appendLine("- **Must not:** $it") }
        if (result.problems.isNotEmpty()) appendLine("- **Automatic checks:** ${result.problems.joinToString("; ")}")
        result.verses?.let { found ->
            val given = SystemPrompt.passagesOf(found).joinToString(", ") { it.first }
            appendLine("- **Verses given:** ${given.ifEmpty { "none (search failed or found nothing)" }}")
        }
        appendLine("- **Grade:** ")
        appendLine()
        if (result.answer != null) {
            appendLine(result.answer.lines().joinToString("\n") { "> $it" })
        } else {
            appendLine("_${result.error}_")
        }
    }
}

/**
 * `./gradlew :server:aiEval` (#126). Arguments: the questions file and the report folder, then
 * optionally a comma-separated list of question IDs to run only those. Reads AI_BASE_URL,
 * AI_API_TOKEN and AI_MODEL like the server does. Never part of `:server:test` or CI: every run
 * spends real free-plan neurons (all questions, about 2,000 of the 10,000 a day).
 *
 * With AI_HELPER_VERSES=true, it searches like the server does with that setting (#287, phase 5),
 * in the verse tables of DATABASE_URL (a local copy loaded by `loadVerseSearch` is enough, and
 * only read), and writes a version-15 report.
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "Usage: AiEval <questions.md> <report folder> [ID,ID,…]" }
    val config = MapApplicationConfig().apply {
        for ((setting, variable) in listOf("baseUrl" to "AI_BASE_URL", "apiToken" to "AI_API_TOKEN", "model" to "AI_MODEL", "disableThinking" to "AI_DISABLE_THINKING")) {
            System.getenv(variable)?.let { put("ai.$setting", it) }
        }
    }
    val provider = OpenAiCompatibleProvider.fromConfig(config)
    if (provider == null) {
        System.err.println("Set AI_BASE_URL, AI_API_TOKEN and AI_MODEL first (server/DEPLOY.md says what each is).")
        exitProcess(1)
    }
    val model = config.property("ai.model").getString()

    val verses = if (System.getenv("AI_HELPER_VERSES")?.toBoolean() == true) {
        val embedding = WorkersAiEmbeddingProvider.fromConfig(config, VerseSearch.MODEL)!!
        val jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5433/bibleapp"
        Database.connect(jdbcUrl, driver = "org.postgresql.Driver", user = System.getenv("DATABASE_USER") ?: "postgres", password = System.getenv("DATABASE_PASSWORD") ?: "postgres")
        println("Verses from search in ${describeDatabase(jdbcUrl)}")
        HelperVerses(VerseSearch(embedding))
    } else {
        null
    }
    val promptVersion = if (verses != null) SystemPrompt.VERSES_PROMPT_VERSION else SystemPrompt.PROMPT_VERSION

    val all = EvalQuestions.parse(File(args[0]).readText())
    val only = args.getOrNull(2)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    val unknown = only - all.map { it.id }.toSet()
    require(unknown.isEmpty()) { "No such questions: $unknown" }
    val questions = if (only.isEmpty()) all else all.filter { it.id in only }

    val date = LocalDate.now(ZoneOffset.UTC)
    val results = runBlocking { evaluate(questions, provider, verses) }
    val folder = File(args[1]).apply { mkdirs() }
    val name = "$date-${model.substringAfterLast('/')}-v$promptVersion${if (only.isEmpty()) "" else "-partial"}.md"
    val file = File(folder, name)
    file.writeText(report(results, model, date, questions.size, promptVersion))
    println("Report: ${file.absolutePath}")
    exitProcess(0)
}
