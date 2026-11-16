package com.application.bibleapp.server.ai.eval

import com.application.bibleapp.server.ai.AiMessage
import com.application.bibleapp.server.ai.AiProvider
import com.application.bibleapp.server.ai.AiRole
import com.application.bibleapp.server.ai.OpenAiCompatibleProvider
import com.application.bibleapp.server.ai.QuotaExhausted
import com.application.bibleapp.server.ai.RoutedGuide
import com.application.bibleapp.server.ai.TopicRouter
import com.application.bibleapp.server.ai.SystemPrompt
import com.application.bibleapp.server.ai.Unavailable
import com.application.bibleapp.server.routes.AI_MAX_TOKENS
import com.application.bibleapp.server.search.EmbeddingKind
import com.application.bibleapp.server.search.VerseSearch
import com.application.bibleapp.server.search.WorkersAiEmbeddingProvider
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
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
    /** The topic guides sent with it (#319, AI_TOPIC_GUIDES); null when the run sends none. */
    val guides: List<RoutedGuide>? = null
) {
    val passed: Boolean get() = error == null && problems.isEmpty()
}

/**
 * Sends each question through the same instructions, token limit and provider as the live route
 * (#126), one at a time. Stops at Cloudflare's daily limit: the rest would fail the same way, and
 * the report says so.
 */
suspend fun evaluate(
    questions: List<EvalQuestion>,
    provider: AiProvider,
    guidesFor: Map<String, List<RoutedGuide>>? = null
): List<EvalResult> {
    val results = mutableListOf<EvalResult>()
    for (question in questions) {
        val guides = guidesFor?.get(question.id).orEmpty()
        // The live route's instructions (#319): v5's, plus section 9 when a guide matched.
        val instructions = SystemPrompt.withGuides(question.language, guides)
        val messages = listOf(
            AiMessage(AiRole.SYSTEM, instructions),
            AiMessage(AiRole.USER, question.ask)
        )
        val result = try {
            val completion = provider.complete(messages, AI_MAX_TOKENS)
            val problems = EvalChecks.check(question, completion.text).toMutableList()
            if (completion.cutOff) problems += "cut off: ran out of tokens"
            EvalResult(question, completion.text, completion.cutOff, completion.neurons, problems, guides = guidesFor?.let { guides })
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
fun report(results: List<EvalResult>, model: String, date: LocalDate, total: Int, version: String = SystemPrompt.PROMPT_VERSION): String = buildString {
    val answered = results.count { it.answer != null }
    val neurons = results.mapNotNull { it.neurons }.sum()
    appendLine("# Bible Helper evaluation, $date")
    appendLine()
    appendLine("- **Model:** `$model`")
    appendLine("- **Instructions:** version $version")
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
        result.guides?.let { guides ->
            appendLine("- **Guide:** ${guides.joinToString(", ") { "${it.guide.id} (%.2f)".format(it.score) }.ifEmpty { "none" }}")
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
    val guided = System.getenv("AI_TOPIC_GUIDES")?.toBoolean() == true

    val all = EvalQuestions.parse(File(args[0]).readText())
    val only = args.getOrNull(2)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    val unknown = only - all.map { it.id }.toSet()
    require(unknown.isEmpty()) { "No such questions: $unknown" }
    val questions = if (only.isEmpty()) all else all.filter { it.id in only }

    // Topic guides (#319): each question is matched first as the server does (the phrase vectors
    // made at build time, one embedding per question, TopicRouter.THRESHOLD unless
    // AI_TOPIC_THRESHOLD says otherwise), and with AI_ONLY_GUIDED only those with a guide are
    // asked, since the others get v5's instructions unchanged.
    val guidesFor = if (guided) {
        val threshold = System.getenv("AI_TOPIC_THRESHOLD")?.toDouble() ?: TopicRouter.THRESHOLD
        val router = TopicRouter.fromResources(VerseSearch.MODEL, threshold)
        val embeddings = WorkersAiEmbeddingProvider.fromConfig(config, VerseSearch.MODEL)!!
        runBlocking {
            questions.chunked(embeddings.maxBatch).flatMap { batch ->
                batch.zip(embeddings.embed(batch.map { it.ask }, EmbeddingKind.QUERY).vectors) { question, vector -> question.id to router.route(vector) }
            }.toMap()
        }
    } else {
        null
    }
    val asked = if (guidesFor != null && System.getenv("AI_ONLY_GUIDED")?.toBoolean() == true) {
        questions.filter { guidesFor[it.id].orEmpty().isNotEmpty() }
    } else {
        questions
    }
    val version = if (guided) SystemPrompt.GUIDES_VERSION else SystemPrompt.PROMPT_VERSION

    val date = LocalDate.now(ZoneOffset.UTC)
    val results = runBlocking { evaluate(asked, provider, guidesFor) }
    val folder = File(args[1]).apply { mkdirs() }
    // Another question set (-PquestionsFile) is named in the report's file name, like "-heldout".
    val set = File(args[0]).nameWithoutExtension.removePrefix("ai_helper_eval").removePrefix("_").let { if (it.isEmpty()) "" else "-$it" }
    val name = "$date-${model.substringAfterLast('/')}-v$version$set${if (only.isEmpty() && asked.size == questions.size) "" else "-partial"}.md"
    val file = File(folder, name)
    file.writeText(report(results, model, date, asked.size, version))
    println("Report: ${file.absolutePath}")
    exitProcess(0)
}
