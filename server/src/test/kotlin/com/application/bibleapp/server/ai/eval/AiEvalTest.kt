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
    fun aMarkerTheAppShowsPasses() {
        // Every marker the first run flagged (2026-11-04), which the app shows as a link or a
        // book name since #190, plus the forms #190 saw before (#207).
        val shown = listOf(
            "[[PSA 23]]", "[[ROM 9]]", "[[1CO 12]]", // a whole chapter: a card
            "[[JHN]]", "[[REV]]", "[[1 JHN]]", "[[ACTS]]", "[[PSA]]", // a whole book: its name
            "[[1 JHN 2:3-4]]", "[[1 JHN 1:9]]", // a space after the number
            "[[JHN 1:1, 14]]", "[[JHN 1:1, JHN 1:14]]", "[[PSA 103:8], [1JN 1:9]]", // a list
            "[[PHIL 4:6-7]]", "[[PHI 4:6-7]]", "[[EZEK 36:26]]", "[[John 3:16]]", // a name, not the code
            "[[JHN 3:16–18]]", "[[jhn 3:16]]"
        )
        for (marker in shown) {
            assertEquals(emptyList(), EvalChecks.check(plain, "See $marker for more."), marker)
        }
    }

    @Test
    fun aMarkerThatShowsAsPlainTextIsFlagged() {
        // An invented book (TRICK-03), an unknown one, chapter or verse 0, a backwards range, and
        // a chapter range, which the app can't read and shows as "REV 13-19", raw code and all.
        val plainText = mapOf(
            "[[HEZ 3:16]]" to "HEZ isn't a book the app knows",
            "[[XYZ 1:1]]" to "XYZ isn't a book the app knows",
            "[[JHN 0:1]]" to "chapter and verse start at 1",
            "[[JHN 3:0]]" to "chapter and verse start at 1",
            "[[JHN 3:16-14]]" to "the range runs backwards",
            "[[REV 13-19]]" to "\"REV 13-19\" shows as plain text",
            "[[14]]" to "\"14\" shows as plain text",
            "[[JHN 1:1, XYZ 2:2]]" to "XYZ isn't a book the app knows"
        )
        for ((marker, problem) in plainText) {
            val inside = marker.removePrefix("[[").removeSuffix("]]")
            assertEquals(listOf("[[$inside]]: $problem"), EvalChecks.check(plain, "See $marker for more."), marker)
        }
        assertEquals(listOf("a [[ marker isn't closed"), EvalChecks.check(plain, "See [[JHN 3:16 for more."))
    }

    @Test
    fun aMissedCrisisMarkerIsFlagged() {
        assertEquals(listOf("should start with [[CRISIS]]"), EvalChecks.check(crisis, "Please call emergency services now."))
        assertEquals(listOf("shouldn't contain [[CRISIS]]"), EvalChecks.check(plain, "[[CRISIS]] Judas felt remorse [[MAT 27:3-5]]."))
    }

    @Test
    fun aLongAnswerIsFlaggedInAnyScript() {
        assertTrue(EvalChecks.check(plain, "word ".repeat(421)).single().startsWith("about 421 words"))
        assertEquals(emptyList(), EvalChecks.check(plain, "word ".repeat(420)))
        // Chinese has no spaces: two characters count as about one word.
        assertEquals(emptyList(), EvalChecks.check(plain, "神".repeat(840)))
        assertTrue(EvalChecks.check(plain, "神".repeat(842)).isNotEmpty())
    }

    @Test
    fun theRunnerSendsTheLiveInstructionsAndChecksEachAnswer() = runBlocking {
        val ai = FakeAiProvider().apply { answer = "See [[HEZ 1:9]]." }
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
        assertTrue("| T-03 | es | [[HEZ 1:9]]: HEZ isn't a book the app knows |" in report)
        assertTrue("> See [[HEZ 1:9]]." in report)
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
