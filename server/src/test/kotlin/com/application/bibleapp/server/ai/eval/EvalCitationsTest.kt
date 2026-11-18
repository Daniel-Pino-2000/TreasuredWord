package com.application.bibleapp.server.ai.eval

import com.application.bibleapp.server.ai.RoutedGuide
import com.application.bibleapp.server.ai.TopicGuide
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The evaluation's grading-free numbers (#321): offline, no neurons. */
class EvalCitationsTest {

    // :server:test runs in server/, so the repo's docs folder is one level up.
    private val main = EvalQuestions.parse(File("../docs/ai_helper_eval.md").readText())
    private val heldOut = EvalQuestions.parse(File("../docs/ai_helper_eval_heldout.md").readText())

    private fun named(must: String) = EvalCitations.namedOn(must).map { it.toString() }
    private fun cited(answer: String) = EvalChecks.passagesIn(answer).map { it.toString() }
    private fun passage(reference: String) = EvalChecks.passagesIn("[[$reference]]").single()

    @Test
    fun theMustLinesNameTheirPassages() {
        val expected = mapOf(
            "CORE-01" to listOf("2TI 3:16", "2PE 1:20-21"),
            "CORE-07" to listOf("JHN 10:28-29", "PHP 1:6", "1JN 2:19"),
            "BAP-02" to listOf("ACT 2:41", "ACT 8:36-38"),
            "BAP-04" to listOf("1CO 11:23-26"), // not "doctrine.md §7"
            "BAP-05" to listOf("1TI 2:12", "1TI 3:1-7"), // "1 Timothy 2:12 and 3:1-7"; not "BF&M 2000"
            "DIS-04" to listOf("1CO 12-14"), // chapters
            "TRAD-01" to listOf("LUK 1:28-48", "1TI 2:5"),
            "LIFE-02" to listOf("MRK 9:24", "PSA 73"), // "Psalm", a whole chapter
            "LIFE-05" to listOf("PSA 119:105"), // not "a Gospel like Mark or John"
            "LIFE-06" to listOf("JHN 3:16", "ROM 5:8"),
            "CRISIS-01" to emptyList(), // [[CRISIS]] isn't a passage
            "CRISIS-05" to listOf("MAT 27:3-5", "ACT 1:18"),
            "TRICK-03" to listOf("2KI 18-20", "2CH 29-32", "ISA 36-39"),
            "TRICK-04" to listOf("PSA 23:1-6"), // a marker
            "LANG-01" to listOf("JHN 3:3-7") // in English and as a marker, once
        )
        for ((id, passages) in expected) {
            assertEquals(passages, named(main.single { it.id == id }.must!!), id)
        }
        assertEquals(listOf("JHN 11:35", "1TH 4:13", "PSA 34:18"), named(heldOut.single { it.id == "HO-06" }.must!!))
    }

    @Test
    fun everyMustLineThatNamesAPassageIsRead() {
        // A digit on a Must line is always a passage, apart from these, so a reference the parser
        // can't read shows up here instead of quietly lowering the count.
        val notPassages = Regex("""§\d+|BF&M 2000|\[\[CRISIS]]|\b[A-Z]+-\d+\b""") // …and "same doctrine as CORE-03"
        for (question in main + heldOut) {
            val must = question.must ?: continue
            if (must.replace(notPassages, "").any(Char::isDigit)) {
                assertTrue(EvalCitations.namedOn(must).isNotEmpty(), "${question.id}: $must")
            }
        }
    }

    @Test
    fun onlyBookNamesAndTheAppsAbbreviationsAreRead() {
        assertEquals(emptyList(), named("Is 1 enough? See Group 3 and Mark or John, or BF&M 2000."))
        assertEquals(listOf("JHN 3:16", "PHP 4:6-7", "SNG 2:4"), named("Jn 3:16, Phil 4:6-7 and Song of Solomon 2:4"))
        assertEquals(listOf("MAT 5:1-7:29"), named("the Sermon on the Mount (Matthew 5:1-7:29)"))
        assertEquals(listOf("ROM 6:1-2", "ROM 8:1"), named("Romans 6:1-2, or 8:1"))
    }

    @Test
    fun anAnswerCitesWhatTheAppShowsAsVerseCards() {
        assertEquals(listOf("JHN 1:1", "JHN 1:14"), cited("See [[JHN 1:1, 14]]."))
        assertEquals(listOf("PSA 103:8", "1JN 1:9"), cited("See [[PSA 103:8], [1JN 1:9]]."))
        assertEquals(listOf("JHN 1:1", "JHN 2:3"), cited("See [[JHN 1:1, 2:3]]."))
        assertEquals(listOf("PHP 4:6-7", "PSA 23", "ROM 8"), cited("[[PHIL 4:6-7]] and [[PSA 23]] and [[ROM 8] too."))
        // Left open before the next marker, and read when every passage in it is real (#222).
        assertEquals(listOf("GEN 2:24", "ROM 1:26-27"), cited("marriage [[GEN 2:24], [[ROM 1:26-27]], the core"))
        assertEquals(listOf("JHN 3:16"), cited("[[XYZ 1:1], [[JHN 3:16]]"))
        // A range of chapters, a link in the app since #339, and one more chapter after it.
        assertEquals(listOf("1CO 12-14", "REV 13-19", "PSA 23", "PSA 24"), cited("Read [[1CO 12-14]], [[REV 13–19]] and [[PSA 23, 24]]."))
        assertTrue(passage("1CO 12-14").overlaps(passage("1CO 13:4")))
        // Not a passage: the crisis marker, a whole book, plain text in the app, backwards ranges.
        assertEquals(emptyList(), cited("[[CRISIS]] Read [[JHN]], [[REV 19-13]], [[HEZ 3:16]], [[JHN 3:16-14]] and [[14]]."))
    }

    @Test
    fun passagesOverlapOnACommonVerse() {
        assertTrue(passage("PSA 23").overlaps(passage("PSA 23:4")))
        assertTrue(passage("PSA 23:4").overlaps(passage("PSA 23")))
        assertTrue(passage("JHN 3:16-18").overlaps(passage("JHN 3:18-20")))
        assertTrue(EvalCitations.namedOn("1 Corinthians 12–14").single().overlaps(passage("1CO 13:4")))
        assertFalse(passage("JHN 3:16").overlaps(passage("JHN 3:17")))
        assertFalse(passage("JHN 3:16").overlaps(passage("JHN 4:16")))
        assertFalse(passage("JHN 3:16").overlaps(passage("1JN 3:16")))
    }

    private val must = "By grace through faith, not works (Ephesians 2:8-9, Romans 10:9-10); the thief on the cross (Luke 23:42-43)."
    private val answer = "Saved by grace [[EPH 2:8]], justified by faith [[ROM 3:28]], by His mercy [[TIT 3:5]]; " +
        "the thief [[LUK 23:43]], and again [[EPH 2:8]]."
    private val guide = TopicGuide("salvation", "Salvation", listOf("How can I be saved?"), listOf("By grace"), listOf("ROM 3:23" to "all have sinned", "TIT 3:5" to "not by works"))

    @Test
    fun anAnswerIsCountedAgainstItsMustLineAndGuide() {
        val guided = EvalCitations.count(must, answer, listOf(guide))
        assertEquals(listOf("EPH 2:8-9", "ROM 10:9-10", "LUK 23:42-43"), guided.named.map { it.toString() })
        assertEquals(listOf("EPH 2:8-9", "LUK 23:42-43"), guided.namedCited.map { it.toString() })
        assertEquals(listOf("EPH 2:8", "ROM 3:28", "TIT 3:5", "LUK 23:43"), guided.cited.map { it.toString() }) // once each
        assertEquals(listOf("TIT 3:5"), guided.fromGuide!!.map { it.toString() })
        assertEquals(listOf("ROM 3:28"), guided.toCheck.map { it.toString() })

        val plain = EvalCitations.count(must, answer, guides = null)
        assertNull(plain.fromGuide)
        assertEquals(listOf("ROM 3:28", "TIT 3:5"), plain.toCheck.map { it.toString() })
    }

    @Test
    fun theReportSummarizesTheNumbers() {
        val question = EvalQuestion(id = "T-01", ask = "How can I be saved?", must = must)
        val other = EvalQuestion(id = "T-02", ask = "What is grace?", must = "Grace is a gift.")
        val plain = report(
            listOf(EvalResult(question, answer), EvalResult(other, "Grace [[EPH 2:8]].")),
            "@cf/test/model", LocalDate.of(2026, 11, 16), total = 2
        )
        assertTrue("- **Must-named passages cited:** 2 of 3 (67%)" in plain)
        assertTrue("- **Other citations, to check by hand:** 3 of 5 (60%)" in plain)
        assertFalse("From guide" in plain)
        assertTrue("| T-01 | en | ok | 2 of 3 (not ROM 10:9-10) | ROM 3:28, TIT 3:5 | – |" in plain)
        assertTrue("| T-02 | en | ok | – | EPH 2:8 | – |" in plain)

        val guided = report(
            listOf(
                EvalResult(question, answer, guides = listOf(RoutedGuide(guide, 0.81))),
                EvalResult(other, "Grace [[EPH 2:8]].", guides = emptyList()),
                EvalResult(other.copy(id = "T-03"), error = "No answer: timeout", guides = emptyList())
            ),
            "@cf/test/model", LocalDate.of(2026, 11, 16), total = 3, version = "16"
        )
        assertTrue("- **Citations from the question's guide:** 1 of 5 (20%)" in guided)
        assertTrue("- **Other citations, to check by hand:** 2 of 5 (40%)" in guided)
        assertTrue("| Question | Language | Checks | Must cited | From guide | To check | Neurons |" in guided)
        assertTrue("| T-01 | en | ok | 2 of 3 (not ROM 10:9-10) | 1 of 4 | ROM 3:28 | – |" in guided)
        assertTrue("| T-03 | en | no answer | – | – | – | – |" in guided)
    }

    @Test
    fun aSecondRunTheSameDayGetsItsOwnFile() {
        val folder = Files.createTempDirectory("ai-eval").toFile()
        try {
            val first = reportFile(folder, "2026-11-16-model-v5")
            assertEquals("2026-11-16-model-v5.md", first.name)
            first.writeText("run 1")
            val second = reportFile(folder, "2026-11-16-model-v5")
            assertEquals("2026-11-16-model-v5-run2.md", second.name)
            second.writeText("run 2")
            assertEquals("2026-11-16-model-v5-run3.md", reportFile(folder, "2026-11-16-model-v5").name)
        } finally {
            folder.deleteRecursively()
        }
    }
}
