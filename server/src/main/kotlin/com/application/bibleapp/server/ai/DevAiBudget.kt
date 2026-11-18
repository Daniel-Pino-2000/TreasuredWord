package com.application.bibleapp.server.ai

import com.application.bibleapp.server.search.EmbeddingKind
import com.application.bibleapp.server.search.EmbeddingProvider
import com.application.bibleapp.server.search.Embeddings
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Keeps the development tools (`aiEval`, `embedTopicGuides`, `topicRoutingEval`,
 * `semanticSearchEval`, `loadVerseSearch`) from using up the Cloudflare account the app shares
 * with them (#343). On 2026-11-16 loading verse vectors reached Cloudflare's API rate limit, and
 * on 2026-11-17 a day of evaluations used up the 10,000 neurons: both times the Bible Helper
 * answered "try again" to everyone.
 *
 * Two rules, for every request a tool sends through [wrap]:
 * - At most [budget] neurons a UTC day (5,000 unless AI_DEV_NEURON_BUDGET says otherwise), counted
 *   in a ledger under [folder], one file a day, shared by every run and worktree on this computer.
 *   Past it the tool gets [QuotaExhausted], so it stops the way it does at Cloudflare's own limit.
 *   Production isn't counted here: the budget is what development may take, leaving the rest.
 * - At least [minGapMillis] between requests (at most ~1,000 per 5 minutes of Cloudflare's 1,200),
 *   so a bulk load never crowds out the app.
 */
class DevAiBudget(
    private val folder: File,
    val budget: Double = DEFAULT_BUDGET,
    private val minGapMillis: Long = MIN_GAP_MILLIS,
    private val clock: Clock = Clock.systemUTC()
) {
    private val gate = Mutex()
    private var lastRequestAt: Long? = null

    private fun ledger(): File = File(folder, "${LocalDate.now(clock.withZone(ZoneOffset.UTC))}.txt")

    /** Neurons the development tools have spent today (UTC), by the ledger. */
    fun spentToday(): Double = ledger().takeIf { it.exists() }
        ?.readLines()?.sumOf { it.trim().toDoubleOrNull() ?: 0.0 } ?: 0.0

    private fun record(neurons: Double) {
        if (neurons <= 0.0) return
        folder.mkdirs()
        ledger().appendText("$neurons\n")
    }

    /** Runs [request] if today's budget isn't spent, after the pause, and records its [neurons]. */
    suspend fun <T> spend(neurons: (T) -> Double?, request: suspend () -> T): T {
        gate.withLock {
            val spent = spentToday()
            if (spent >= budget) {
                val message = "Development's ${"%.0f".format(budget)} neurons for today are used up (${"%.0f".format(spent)}), " +
                    "leaving the rest for the app. Carry on after 00:00 UTC, or set AI_DEV_NEURON_BUDGET (#343)."
                System.err.println(message)
                throw QuotaExhausted(message)
            }
            lastRequestAt?.let { last ->
                val wait = minGapMillis - (System.nanoTime() - last) / 1_000_000
                if (wait > 0) delay(wait)
            }
            lastRequestAt = System.nanoTime()
        }
        return request().also { result -> neurons(result)?.let(::record) }
    }

    /** [provider], with every answer counted and paced. */
    fun wrap(provider: AiProvider): AiProvider = object : AiProvider {
        override suspend fun complete(messages: List<AiMessage>, maxTokens: Int): AiCompletion =
            spend({ it.neurons }) { provider.complete(messages, maxTokens) }

        override suspend fun stream(messages: List<AiMessage>, maxTokens: Int, onText: suspend (String) -> Unit): AiCompletion =
            spend({ it.neurons }) { provider.stream(messages, maxTokens, onText) }
    }

    /** [provider], with every request counted and paced. */
    fun wrap(provider: EmbeddingProvider): EmbeddingProvider = object : EmbeddingProvider {
        override val model: String get() = provider.model
        override val maxBatch: Int get() = provider.maxBatch
        override suspend fun embed(texts: List<String>, kind: EmbeddingKind): Embeddings =
            spend({ it.neurons }) { provider.embed(texts, kind) }
    }

    /** One line for a tool to print when it starts. */
    fun summary(): String = "Development budget (#343): ${"%.0f".format(spentToday())} of ${"%.0f".format(budget)} neurons used today (UTC)"

    companion object {
        /** Half the free 10,000 a day: two full `aiEval` runs and change, and ~140 answers left for the app. */
        const val DEFAULT_BUDGET = 5_000.0
        /** ~3 requests a second at most: ~1,000 of Cloudflare's 1,200 per 5 minutes. */
        const val MIN_GAP_MILLIS = 300L

        /**
         * The tools' budget: AI_DEV_NEURON_BUDGET or [DEFAULT_BUDGET], with its ledger in
         * `~/.treasuredword-dev/ai-neurons`, outside the repository so every worktree shares it.
         */
        fun fromEnvironment(): DevAiBudget = DevAiBudget(
            folder = File(System.getProperty("user.home"), ".treasuredword-dev/ai-neurons"),
            budget = System.getenv("AI_DEV_NEURON_BUDGET")?.toDoubleOrNull() ?: DEFAULT_BUDGET
        )
    }
}
