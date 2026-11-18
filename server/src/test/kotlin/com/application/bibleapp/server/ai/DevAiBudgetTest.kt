package com.application.bibleapp.server.ai

import com.application.bibleapp.server.routes.FakeAiProvider
import com.application.bibleapp.server.routes.MutableClock
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The development tools' daily neuron budget and pause between requests (#343). */
class DevAiBudgetTest {

    private val folder = Files.createTempDirectory("ai-neurons").toFile()
    private val clock = MutableClock(Instant.parse("2026-11-17T10:00:00Z"))
    private val conversation = listOf(AiMessage(AiRole.USER, "What is grace?"))

    @Test
    fun answersAreCountedInTodaysLedgerSharedByEveryRun() = runBlocking {
        // FakeAiProvider reports 31 neurons an answer.
        DevAiBudget(folder, budget = 100.0, minGapMillis = 0, clock = clock).wrap(FakeAiProvider()).complete(conversation, 700)
        val nextRun = DevAiBudget(folder, budget = 100.0, minGapMillis = 0, clock = clock)
        nextRun.wrap(FakeAiProvider()).complete(conversation, 700)

        assertEquals(62.0, nextRun.spentToday())
    }

    @Test
    fun atTheBudgetTheToolStopsAsAtCloudflaresLimitUntilTheNextUtcDay() = runBlocking {
        val ai = FakeAiProvider()
        val budget = DevAiBudget(folder, budget = 60.0, minGapMillis = 0, clock = clock)
        val provider = budget.wrap(ai)
        provider.complete(conversation, 700)
        provider.complete(conversation, 700)

        assertFailsWith<QuotaExhausted> { provider.complete(conversation, 700) }
        assertEquals(2, ai.calls.size)

        clock.now = clock.now.plus(Duration.ofDays(1))
        provider.complete(conversation, 700)
        assertEquals(31.0, budget.spentToday())
    }

    @Test
    fun requestsArePacedApart() = runBlocking {
        val provider = DevAiBudget(folder, budget = 1_000.0, minGapMillis = 200, clock = clock).wrap(FakeAiProvider())
        val started = System.nanoTime()
        repeat(3) { provider.complete(conversation, 700) }

        assertTrue((System.nanoTime() - started) / 1_000_000 >= 400)
    }
}
