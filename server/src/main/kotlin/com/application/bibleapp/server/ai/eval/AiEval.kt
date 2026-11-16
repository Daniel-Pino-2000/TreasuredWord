package com.application.bibleapp.server.ai.eval

import com.application.bibleapp.server.ai.AiMessage
import com.application.bibleapp.server.ai.AiProvider
import com.application.bibleapp.server.ai.AiRole
import com.application.bibleapp.server.ai.OpenAiCompatibleProvider
import com.application.bibleapp.server.ai.QuotaExhausted
import com.application.bibleapp.server.ai.RoutedGuide
import com.application.bibleapp.server.ai.TopicGuides
import com.application.bibleapp.server.ai.TopicRouter
import com.application.bibleapp.server.ai.SystemPrompt
import com.application.bibleapp.server.ai.Unavailable
import com.application.bibleapp.server.routes.AI_MAX_TOKENS
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
    /** The topic guides sent with it (pilot, AI_TOPIC_GUIDES); null when the run sends none. */
    val guides: List<RoutedGuide>? = null
) {
    val passed: Boolean get() = error == null && problems.isEmpty()

    /** The grading-free numbers (#321), or null without an answer. */
    val citations: CitationCount? by lazy {
        answer?.let { EvalCitations.count(question.must, it, guides?.map { routed -> routed.guide }) }
    }
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
        val instructions = SystemPrompt.forLanguage(question.language) +
            (TopicGuides.section(guides)?.let { "\n\n$it" } ?: "")
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

/**
 * The report people read and grade: a summary with the grading-free numbers (#321), a table, then
 * each question with its answer.
 */
fun report(results: List<EvalResult>, model: String, date: LocalDate, total: Int, version: String = SystemPrompt.PROMPT_VERSION): String = buildString {
    val answered = results.count { it.answer != null }
    val neurons = results.mapNotNull { it.neurons }.sum()
    val counts = results.mapNotNull { it.citations }
    val guided = results.any { it.guides != null }
    val cited = counts.sumOf { it.cited.size }
    appendLine("# Bible Helper evaluation, $date")
    appendLine()
    appendLine("- **Model:** `$model`")
    appendLine("- **Instructions:** version $version")
    appendLine("- **Answered:** $answered of $total questions")
    appendLine("- **Automatic checks passed:** ${results.count { it.passed }} of ${results.size}")
    appendLine("- **Neurons used:** ${"%.0f".format(neurons)} of the free 10,000 a day")
    appendLine("- **Must-named passages cited:** ${EvalCitations.share(counts.sumOf { it.namedCited.size }, counts.sumOf { it.named.size })}")
    if (guided) appendLine("- **Citations from the question's guide:** ${EvalCitations.share(counts.sumOf { it.fromGuide.orEmpty().size }, cited)}")
    appendLine("- **Other citations, to check by hand:** ${EvalCitations.share(counts.sumOf { it.toCheck.size }, cited)}")
    appendLine()
    appendLine("Grade each answer with the rubric in `docs/ai_helper_eval.md` (Good, Needs work or Wrong) and summarize in the PR.")
    appendLine("The citation numbers need no grading (\"Comparing two versions\" there): check that each passage in")
    appendLine("**To check**, which is on neither the Must line nor ${if (guided) "the guide" else "a guide"}, makes the point it's cited for.")
    appendLine()
    appendLine("| Question | Language | Checks | Must cited |${if (guided) " From guide |" else ""} To check | Neurons |")
    appendLine("|---|---|---|---|${if (guided) "---|" else ""}---|---|")
    for (result in results) {
        val checks = when {
            result.error != null -> "no answer"
            result.problems.isEmpty() -> "ok"
            else -> result.problems.joinToString("; ").replace("|", "\\|")
        }
        val count = result.citations
        val mustCited = when {
            count == null || count.named.isEmpty() -> "–"
            else -> "${count.namedCited.size} of ${count.named.size}" +
                (count.named - count.namedCited.toSet()).takeIf { it.isNotEmpty() }?.let { missing -> " (not ${missing.joinToString(", ")})" }.orEmpty()
        }
        val fromGuide = if (guided) " ${count?.let { "${it.fromGuide.orEmpty().size} of ${it.cited.size}" } ?: "–"} |" else ""
        val toCheck = count?.toCheck?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "–"
        appendLine("| ${result.question.id} | ${result.question.language} | $checks | $mustCited |$fromGuide $toCheck | ${result.neurons?.let { "%.1f".format(it) } ?: "–"} |")
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
/** Pilot: v5's instructions plus the matching topic guides (`topics.md`), evaluation only. */
const val GUIDES_VERSION = "16"

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

    // Topic guides (pilot): each question is matched first, and with AI_ONLY_GUIDED only those
    // with a guide are asked, since the others get v5's instructions unchanged.
    val guidesFor = if (guided) {
        val threshold = requireNotNull(System.getenv("AI_TOPIC_THRESHOLD")?.toDouble()) { "Set AI_TOPIC_THRESHOLD" }
        val router = TopicRouter(TopicGuides.load(), WorkersAiEmbeddingProvider.fromConfig(config, VerseSearch.MODEL)!!, threshold)
        runBlocking {
            router.prepare()
            questions.associate { it.id to router.pick(router.scores(it.ask)) }
        }
    } else {
        null
    }
    val asked = if (guidesFor != null && System.getenv("AI_ONLY_GUIDED")?.toBoolean() == true) {
        questions.filter { guidesFor[it.id].orEmpty().isNotEmpty() }
    } else {
        questions
    }
    val version = if (guided) GUIDES_VERSION else SystemPrompt.PROMPT_VERSION

    val date = LocalDate.now(ZoneOffset.UTC)
    val results = runBlocking { evaluate(asked, provider, guidesFor) }
    val folder = File(args[1]).apply { mkdirs() }
    // Another question set (-PquestionsFile) is named in the report's file name, like "-heldout".
    val set = File(args[0]).nameWithoutExtension.removePrefix("ai_helper_eval").removePrefix("_").let { if (it.isEmpty()) "" else "-$it" }
    val name = "$date-${model.substringAfterLast('/')}-v$version$set${if (only.isEmpty() && asked.size == questions.size) "" else "-partial"}"
    val file = reportFile(folder, name)
    file.writeText(report(results, model, date, asked.size, version))
    println("Report: ${file.absolutePath}")
    exitProcess(0)
}

/**
 * `<name>.md`, or `<name>-run2.md`, `-run3` and so on when it's taken: a version is compared on
 * two runs (#321), and a second run the same day mustn't overwrite the first.
 */
fun reportFile(folder: File, name: String): File =
    generateSequence(1) { it + 1 }
        .map { run -> File(folder, if (run == 1) "$name.md" else "$name-run$run.md") }
        .first { !it.exists() }
