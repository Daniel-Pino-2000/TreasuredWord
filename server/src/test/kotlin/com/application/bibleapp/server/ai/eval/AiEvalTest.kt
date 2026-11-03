package com.application.bibleapp.server.ai.eval

import com.application.bibleapp.server.ai.AiRole
import com.application.bibleapp.server.ai.QuotaExhausted
import com.application.bibleapp.server.ai.Unavailable
import com.application.bibleapp.server.routes.FakeAiProvider
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The evaluation runner (#126) with the fake provider: no network, no neurons. */
class AiEvalTest {

    // :server:test runs in server/, so the repo's docs folder is one level up.
    private val questionsFile = File("../docs/ai_helper_eval.md")

    private val plain = EvalQuestion(id = "T-01", ask = "What is grace?")
    private val crisis = plain.copy(id = "T-02", crisis = CrisisExpectation.YES)

    @Test
    fun everyQuestionInTheRealFileIsRead() {
        val markdown = questionsFile.readText()
        val questions = EvalQuestions.parse(markdown)

        val lines = markdown.lines().map { it.trimEnd() }
        val headings = lines.drop(lines.indexOf("## Questions")).count { it.startsWith("### ") }
        assertEquals(headings, questions.size)
        assertTrue(questions.all { it.ask.isNotBlank() && it.must != null })
        assertEquals("es", questions.single { it.id == "LANG-01" }.language)
        assertEquals("en", questions.single { it.id == "CORE-01" }.language)
        assertEquals(CrisisExpectation.YES, questions.single { it.id == "CRISIS-01" }.crisis)
        assertEquals(CrisisExpectation.NO, questions.single { it.id == "CRISIS-05" }.crisis)
        // The example in "How a question is written" comes before "## Questions" and isn't one.
        assertEquals(1, questions.count { it.id == "CORE-01" })
    }

    @Test
    fun aGoodAnswerPassesEveryCheck() {
        assertEquals(emptyList(), EvalChecks.check(plain, "Grace is God's undeserved favor [[EPH 2:8-9]], seen in Christ [[JHN 1:14]] and [[1JN 4:10]]."))
        assertEquals(emptyList(), EvalChecks.check(crisis, "[[CRISIS]] You matter to God [[PSA 34:18]]. Please call emergency services now."))
    }

    @Test
    fun aBadBookCodeOrMalformedMarkerIsFlagged() {
        // The ones #190 saw from real answers, and an invented book (TRICK-03).
        for (bad in listOf("[[1 JHN 1:9]]", "[[EZEK 36:26]]", "[[PHI 4:6-7]]", "[[PSA]]", "[[HEZ 3:16]]", "[[JHN 3:16-14]]", "[[JHN 3:16")) {
            assertTrue(EvalChecks.check(plain, "See $bad for more.").isNotEmpty(), bad)
        }
    }

    @Test
    fun aMissedCrisisMarkerIsFlagged() {
        assertEquals(listOf("should start with [[CRISIS]]"), EvalChecks.check(crisis, "Please call emergency services now."))
        assertEquals(listOf("shouldn't contain [[CRISIS]]"), EvalChecks.check(plain, "[[CRISIS]] Judas felt remorse [[MAT 27:3-5]]."))
    }

    @Test
    fun aLongAnswerIsFlaggedInAnyScript() {
        assertTrue(EvalChecks.check(plain, "word ".repeat(351)).single().startsWith("about 351 words"))
        assertEquals(emptyList(), EvalChecks.check(plain, "word ".repeat(350)))
        // Chinese has no spaces: two characters count as about one word.
        assertEquals(emptyList(), EvalChecks.check(plain, "神".repeat(700)))
        assertTrue(EvalChecks.check(plain, "神".repeat(702)).isNotEmpty())
    }

    @Test
    fun theRunnerSendsTheLiveInstructionsAndChecksEachAnswer() = runBlocking {
        val ai = FakeAiProvider().apply { answer = "See [[1 JHN 1:9]]." }
        val spanish = plain.copy(id = "T-03", language = "es")

        val results = evaluate(listOf(spanish, crisis), ai)

        assertEquals(2, results.size)
        assertTrue(results.none { it.passed })
        assertTrue(results[1].problems.contains("should start with [[CRISIS]]"))
        val sent = ai.calls.first()
        assertEquals(700, sent.maxTokens)
        assertEquals(AiRole.SYSTEM, sent.messages.first().role)
        assertTrue("Always answer in Spanish" in sent.messages.first().content)
        assertEquals("What is grace?", sent.messages.last().content)

        val report = report(results, "@cf/test/model", LocalDate.of(2026, 11, 2), total = 2)
        assertTrue("| T-03 | es | [[1 JHN 1:9]]: not BOOK C:V or BOOK C:V-V |" in report)
        assertTrue("> See [[1 JHN 1:9]]." in report)
    }

    @Test
    fun theRunnerStopsWhenTheDailyAllowanceRunsOut() = runBlocking {
        val ai = FakeAiProvider().apply { failure = QuotaExhausted("3036") }

        val results = evaluate(listOf(plain, crisis), ai)

        assertEquals(1, results.size)
        assertFalse(results.single().passed)
        assertEquals(1, ai.calls.size)
    }

    @Test
    fun anUnavailableAnswerIsRecordedAndTheRunGoesOn() = runBlocking {
        val ai = FakeAiProvider().apply { failure = Unavailable("timeout") }

        val results = evaluate(listOf(plain, crisis), ai)

        assertEquals(2, results.size)
        assertTrue(results.all { it.error != null })
    }
}
