package com.application.bibleapp.server.ai.eval

import com.application.bibleapp.server.ai.RoutedGuide
import com.application.bibleapp.server.ai.TopicGuide
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Blind A/B grading of two reports (#321): local files only, no network, no neurons. */
class BlindGradingTest {

    private val questions = (1..6).map { EvalQuestion(id = "Q-0$it", ask = "Question $it?", must = "Point $it; cite John 3:$it.") }
    private val guide = TopicGuide("salvation", "Salvation", listOf("How can I be saved?"), listOf("By grace"), listOf("JHN 3:16" to "God so loved"))

    // Two runs as aiEval writes them: the old version without guides, the new one with a Guide
    // line, a different version and automatic-check problems, none of which may reach the sheet.
    private val oldReport = report(
        questions.map { EvalResult(it, "Old answer to ${it.id} [[JHN 3:1]].\n\nSecond paragraph.") },
        "@cf/test/model", LocalDate.of(2026, 11, 16), total = 6, version = "5"
    )
    private val newReport = report(
        questions.map {
            if (it.id == "Q-06") EvalResult(it, error = "No answer: timeout", guides = emptyList())
            else EvalResult(it, "New answer to ${it.id} [[JHN 3:16]].", problems = listOf("about 400 words"), guides = listOf(RoutedGuide(guide, 0.8)))
        },
        "@cf/test/model", LocalDate.of(2026, 11, 16), total = 6, version = "16"
    )
    private val old = LabeledReport("2026-11-16-model-v5", BlindGrading.parseReport(oldReport))
    private val new = LabeledReport("2026-11-16-model-v16", BlindGrading.parseReport(newReport))

    @Test
    fun aReportIsReadBack() {
        assertEquals(questions.map { it.id }, old.answers.map { it.id })
        val first = old.answers.first()
        assertEquals("Old answer to Q-01 [[JHN 3:1]].\n\nSecond paragraph.", first.answer)
        assertEquals(
            listOf("- **Ask:** Question 1?", "- **Language:** en · **Crisis:** no", "- **Must:** Point 1; cite John 3:1."),
            first.questionLines
        )
        assertEquals(null, new.answers.last().answer)
    }

    @Test
    fun theSheetHidesWhichVersionWroteEachAnswer() {
        val blind = BlindGrading.sheet(old, new, seed = 42, name = "blind1")

        // Q-06 has no answer in the new report, so it's left out, and the key says so.
        assertEquals(listOf("Q-06: no answer in 2026-11-16-model-v16"), blind.skipped)
        assertTrue("# skipped Q-06: no answer in 2026-11-16-model-v16" in blind.key)
        assertFalse("## Q-06" in blind.sheet)

        for (hidden in listOf("v5", "v16", "**Guide:**", "**Grade:**", "Automatic checks", "Instructions", "Must cited", "salvation", "400 words")) {
            assertFalse(hidden in blind.sheet, hidden)
        }
        for (id in listOf("Q-01", "Q-02", "Q-03", "Q-04", "Q-05")) {
            assertTrue("> Old answer to $id [[JHN 3:1]]." in blind.sheet)
            assertTrue("> New answer to $id [[JHN 3:16]]." in blind.sheet)
            assertTrue("$id A= B=" in blind.grades)
        }
        // One shared Must line per question, and two answers.
        assertEquals(5, Regex("""(?m)^- \*\*Must:\*\*""").findAll(blind.sheet).count())
        assertEquals(5, Regex("""(?m)^### A$""").findAll(blind.sheet).count())
        assertTrue("> \n> Second paragraph." in blind.sheet)

        // Half the questions (rounded up) have the first report as A, so neither letter leans.
        val keyLines = blind.key.lines().filter { it.startsWith("Q-") }
        assertEquals(5, keyLines.size)
        assertEquals(3, keyLines.count { it.contains("A=2026-11-16-model-v5 ") })
        assertTrue("first: 2026-11-16-model-v5" in blind.key)
        assertTrue("# seed 42" in blind.key)
    }

    @Test
    fun theSeedDecidesTheOrder() {
        val once = BlindGrading.sheet(old, new, seed = 42, name = "blind1")
        assertEquals(once, BlindGrading.sheet(old, new, seed = 42, name = "blind1"))
        val orders = (1L..20L).map { seed -> BlindGrading.sheet(old, new, seed, "blind1").key.lines().filter { it.startsWith("Q-") } }
        assertTrue(orders.distinct().size > 1)
    }

    @Test
    fun questionsInOnlyOneReportAreLeftOut() {
        val fewer = LabeledReport("other", old.answers.drop(1))
        val blind = BlindGrading.sheet(old, fewer, seed = 1, name = "b")
        assertEquals(listOf("Q-01: not in other"), blind.skipped)
        assertFalse("## Q-01" in blind.sheet)
        assertEquals(listOf("Q-01: not in other"), BlindGrading.sheet(fewer, old, 1, "b").skipped)
        assertFailsWith<IllegalArgumentException> { BlindGrading.sheet(old, LabeledReport("none", emptyList()), 1, "b") }
    }

    /** Fills [blind]'s grades: [first] for the first report's answers, [second] for the second's. */
    private fun grade(blind: BlindSheet, first: Map<String, String>, second: Map<String, String>): String =
        blind.key.lines().filter { it.startsWith("Q-") }.joinToString("\n") { line ->
            val id = line.substringBefore(':')
            val firstIsA = line.contains("A=2026-11-16-model-v5 ")
            val (a, b) = if (firstIsA) first[id] to second[id] else second[id] to first[id]
            if (a == null) "$id A= B=" else "$id A=$a (a note (with brackets)) B=$b"
        }

    @Test
    fun gradesAreMatchedToTheirReportsThroughTheKey() {
        val blind = BlindGrading.sheet(old, new, seed = 7, name = "blind1")
        val grades = grade(
            blind,
            first = mapOf("Q-01" to "Needs work", "Q-02" to "Good", "Q-03" to "Wrong", "Q-04" to "NeedsWork"),
            second = mapOf("Q-01" to "Good", "Q-02" to "Good", "Q-03" to "needs  work", "Q-04" to "Wrong")
        )
        val sheet = BlindGrading.score("blind1", blind.key, "# a comment\n\n$grades\n")

        assertEquals("2026-11-16-model-v5", sheet.firstLabel)
        assertEquals("2026-11-16-model-v16", sheet.secondLabel)
        assertEquals(Grade.NEEDS_WORK to Grade.GOOD, sheet.grades["Q-01"])
        assertEquals(Grade.GOOD to Grade.GOOD, sheet.grades["Q-02"])
        assertEquals(Grade.WRONG to Grade.NEEDS_WORK, sheet.grades["Q-03"])
        assertEquals(Grade.NEEDS_WORK to Grade.WRONG, sheet.grades["Q-04"])
        assertEquals(listOf("Q-05"), sheet.ungraded)
    }

    @Test
    fun aGradesFileThatCantBeReadIsAnError() {
        val blind = BlindGrading.sheet(old, new, seed = 7, name = "blind1")
        for (grades in listOf("Q-01 A=Good B=", "Q-01 A=Good A=Wrong B=Good", "Q-99 A=Good B=Good", "Q-01 A=Good B=Good\nQ-01 A=Good B=Good", "Q-01 A=Fine B=Good")) {
            assertFailsWith<IllegalArgumentException>(grades) { BlindGrading.score("blind1", blind.key, grades) }
        }
        assertFailsWith<IllegalArgumentException> { BlindGrading.score("blind1", "Q-01: A=x B=y", "") }
    }

    @Test
    fun theResultsListEachQuestionsChangeAndEachReportsTotals() {
        fun sheet(name: String, grades: Map<String, Pair<Grade, Grade>>) = GradedSheet(name, "$name-v5", "$name-v16", grades, emptyList())
        val g = Grade.GOOD
        val n = Grade.NEEDS_WORK
        val w = Grade.WRONG
        val results = BlindGrading.results(
            listOf(
                sheet("blind1", mapOf("Q-01" to (n to g), "Q-02" to (g to n), "Q-03" to (g to g), "Q-04" to (w to g))),
                sheet("blind2", mapOf("Q-01" to (n to g), "Q-02" to (g to g), "Q-03" to (g to g), "Q-04" to (g to n))).copy(ungraded = listOf("Q-05"))
            )
        )
        assertTrue("| blind1 | first: blind1-v5 | 2 of 4 (50%) | 1 | 1 |" in results)
        assertTrue("| blind1 | second: blind1-v16 | 3 of 4 (75%) | 1 | 0 |" in results)
        assertTrue("| blind2 | second: blind2-v16 | 3 of 4 (75%) | 1 | 0 |" in results)
        assertTrue("| Q-01 | Needs work, Needs work | Good, Good | better in 2 of 2 |" in results)
        assertTrue("| Q-02 | Good, Good | Needs work, Good | worse in 1 of 2 |" in results)
        assertTrue("| Q-03 | Good, Good | Good, Good | same |" in results)
        assertTrue("| Q-04 | Wrong, Good | Good, Needs work | mixed |" in results)
        assertTrue("Not graded in blind2, so left out: Q-05" in results)
        assertTrue("Questions where the second report is better: 1; worse: 1; mixed: 1; the same: 1." in results)
    }

    @Test
    fun theTasksWriteTheFilesAndNeverOverwriteGrades() {
        val root = Files.createTempDirectory("repo").toFile()
        try {
            val folder = File(root, "server/build/ai-eval").apply { mkdirs() }
            File(folder, "old.md").writeText(oldReport)
            File(root, "new.md").writeText(newReport)
            assertEquals(File(folder, "old.md"), BlindGrading.resolveReport("old.md", root, folder))
            assertEquals(File(root, "new.md"), BlindGrading.resolveReport("new.md", root, folder))
            assertFailsWith<IllegalArgumentException> { BlindGrading.resolveReport("missing.md", root, folder) }

            writeSheet(listOf(root.path, folder.path, "old.md", "new.md", "blind1", "3"))
            val key = File(folder, "blind1-key.txt").readText()
            assertTrue("first: old" in key && "second: new" in key)
            assertTrue(File(folder, "blind1.md").isFile)
            assertFailsWith<IllegalArgumentException> { writeSheet(listOf(root.path, folder.path, "old.md", "new.md", "blind1", "4")) }

            // Grade every answer Good, then score: the totals are equal and nothing changed.
            val grades = File(folder, "blind1-grades.txt")
            grades.writeText(grades.readText().replace("A= B=", "A=Good B=Good"))
            writeResults(listOf(folder.path, "blind1"))
            val results = File(folder, "blind1-results.md").readText()
            assertTrue("| blind1 | first: old | 5 of 5 (100%) | 0 | 0 |" in results)
            assertTrue("the same: 5." in results)

            // The same file name twice still gets two labels.
            File(root, "old.md").writeText(oldReport)
            writeSheet(listOf(root.path, folder.path, "old.md", "${folder.path}/old.md", "blind2", "3"))
            val twice = File(folder, "blind2-key.txt").readText()
            assertTrue("first: old (first)" in twice && "second: old (second)" in twice)
            assertNotEquals(key, twice)
        } finally {
            root.deleteRecursively()
        }
    }
}
