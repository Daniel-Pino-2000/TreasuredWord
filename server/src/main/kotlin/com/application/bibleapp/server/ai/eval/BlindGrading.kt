package com.application.bibleapp.server.ai.eval

import java.io.File
import kotlin.random.Random
import kotlin.system.exitProcess

/** One question of an `aiEval` report, read back for a blind A/B sheet (#321). */
data class ReportedAnswer(
    val id: String,
    /** The lines a grader sees: Ask, Language and Crisis, Must, Must not. */
    val questionLines: List<String>,
    /** The answer, or null when the report has none (an error, or the daily limit). */
    val answer: String?
)

/** A report and the name it goes by in the key: its file name. */
data class LabeledReport(val label: String, val answers: List<ReportedAnswer>)

/** A blind sheet's three files, and the questions left out because one report has no answer. */
data class BlindSheet(val sheet: String, val key: String, val grades: String, val skipped: List<String>)

enum class Grade(val text: String) {
    WRONG("Wrong"), NEEDS_WORK("Needs work"), GOOD("Good");

    companion object {
        fun parse(text: String): Grade = when (text.lowercase().replace(Regex("\\s+"), "")) {
            "good" -> GOOD
            "needswork" -> NEEDS_WORK
            "wrong" -> WRONG
            else -> throw IllegalArgumentException("Not a grade: $text (Good, Needs work or Wrong)")
        }
    }
}

/** One graded sheet: each question's grades for the first and the second report. */
data class GradedSheet(
    val name: String,
    val firstLabel: String,
    val secondLabel: String,
    /** Question id to (first report's grade, second report's grade), in the key's order. */
    val grades: Map<String, Pair<Grade, Grade>>,
    val ungraded: List<String>
)

/**
 * Blind A/B grading (#321, the audit's section 3): two reports become one sheet where each
 * question's two answers are A and B in a seeded random order, so the grader can't tell the
 * versions apart. The key that says which is which is a separate file, opened only after the
 * grades are written; [score] then lists each question's change and each version's totals.
 */
object BlindGrading {

    // Only these lines reach the sheet: a report's Guide, Automatic checks and Grade lines, and
    // anything added later, would tell the versions apart.
    private val shownLines = listOf("- **Ask:**", "- **Language:**", "- **Must:**", "- **Must not:**")
    private val keyLine = Regex("""^(\S+): A=(.+) B=(.+)$""")
    private val gradeToken = Regex("""(?i)(?<![\p{L}\d])([AB])\s*=\s*(good|needs\s*work|wrong)(?![\p{L}])""")

    /** The questions of an `aiEval` report (its `## ID` sections), in order. */
    fun parseReport(markdown: String): List<ReportedAnswer> =
        markdown.replace("\r\n", "\n").split(Regex("(?m)^## ")).drop(1).map { block ->
            val lines = block.lines()
            val quoted = lines.filter { it.startsWith(">") }
            ReportedAnswer(
                id = lines.first().trim(),
                questionLines = lines.filter { line -> shownLines.any { line.startsWith(it) } },
                answer = quoted.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.removePrefix(">").removePrefix(" ") }
            )
        }

    /**
     * The sheet, key and grades template for [first] and [second]: every question both answered,
     * in [first]'s order, with the question's lines shown once (from [first]). Half the questions
     * get [first] as A, chosen by [seed], so neither letter leans to one version.
     */
    fun sheet(first: LabeledReport, second: LabeledReport, seed: Long, name: String): BlindSheet {
        require(first.label != second.label) { "The two reports need different labels" }
        val secondById = second.answers.associateBy { it.id }
        val firstIds = first.answers.map { it.id }.toSet()
        val skipped = first.answers.mapNotNull { ours ->
            val theirs = secondById[ours.id]
            when {
                theirs == null -> "${ours.id}: not in ${second.label}"
                ours.answer == null -> "${ours.id}: no answer in ${first.label}"
                theirs.answer == null -> "${ours.id}: no answer in ${second.label}"
                else -> null
            }
        } + second.answers.filter { it.id !in firstIds }.map { "${it.id}: not in ${first.label}" }
        val pairs = first.answers.filter { it.answer != null && secondById[it.id]?.answer != null }
            .map { it to secondById.getValue(it.id) }
        require(pairs.isNotEmpty()) { "The two reports have no answered question in common" }
        val firstIsA = List(pairs.size) { it < (pairs.size + 1) / 2 }.shuffled(Random(seed))

        val sheet = buildString {
            appendLine("# Blind grading: $name")
            appendLine()
            appendLine("${pairs.size} questions, each answered by two versions of the Bible Helper. Which version wrote A")
            appendLine("and which wrote B changes from question to question. Grade every answer with the rubric in")
            appendLine("`docs/ai_helper_eval.md` in `$name-grades.txt`, and only then open `$name-key.txt`.")
            pairs.forEachIndexed { index, (ours, theirs) ->
                val (a, b) = if (firstIsA[index]) ours to theirs else theirs to ours
                appendLine()
                appendLine("## ${ours.id}")
                appendLine()
                ours.questionLines.forEach { appendLine(it) }
                for ((letter, answer) in listOf("A" to a, "B" to b)) {
                    appendLine()
                    appendLine("### $letter")
                    appendLine()
                    appendLine(answer.answer!!.lines().joinToString("\n") { "> $it" })
                }
            }
        }
        val key = buildString {
            appendLine("# Key to $name.md: which report wrote A and which wrote B. Open it only after grading every answer.")
            appendLine("# seed $seed")
            skipped.forEach { appendLine("# skipped $it") }
            appendLine("first: ${first.label}")
            appendLine("second: ${second.label}")
            pairs.forEachIndexed { index, (ours, _) ->
                val (a, b) = if (firstIsA[index]) first.label to second.label else second.label to first.label
                appendLine("${ours.id}: A=$a B=$b")
            }
        }
        val grades = buildString {
            appendLine("# Grades for $name.md: after A= and B=, write Good, Needs work or Wrong; a note in parentheses")
            appendLine("# can follow each. Lines starting with # are ignored. Then: ./gradlew :server:aiEvalBlindScore -Psheets=$name")
            pairs.forEach { (ours, _) -> appendLine("${ours.id} A= B=") }
        }
        return BlindSheet(sheet, key, grades, skipped)
    }

    /** Questions whose lines differ between the reports: graded against [first]'s, so worth a look. */
    fun differingQuestions(first: LabeledReport, second: LabeledReport): List<String> {
        val secondById = second.answers.associateBy { it.id }
        return first.answers.filter { ours -> secondById[ours.id]?.let { it.questionLines != ours.questionLines } == true }.map { it.id }
    }

    /** [keyText] and the filled-in [gradesText] of the sheet [name], as each report's grades. */
    fun score(name: String, keyText: String, gradesText: String): GradedSheet {
        val keyLines = keyText.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        fun label(role: String) = requireNotNull(keyLines.firstOrNull { it.startsWith("$role: ") }?.removePrefix("$role: ")) {
            "$name-key.txt has no \"$role:\" line"
        }
        val firstLabel = label("first")
        val secondLabel = label("second")
        val firstIsA = keyLines.mapNotNull { keyLine.matchEntire(it) }.associate { match ->
            val (id, a, b) = match.destructured
            require(setOf(a, b) == setOf(firstLabel, secondLabel)) { "$name-key.txt: $id doesn't name both reports" }
            id to (a == firstLabel)
        }

        val letterGrades = mutableMapOf<String, Pair<Grade, Grade>>()
        for (line in gradesText.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            val id = line.substringBefore(' ')
            require(id in firstIsA) { "$name-grades.txt: $id isn't in the key" }
            require(id !in letterGrades) { "$name-grades.txt: $id is graded twice" }
            val tokens = gradeToken.findAll(line.removePrefix(id)).map { it.groupValues[1].uppercase() to Grade.parse(it.groupValues[2]) }.toList()
            if (tokens.isEmpty()) continue
            val a = tokens.filter { it.first == "A" }
            val b = tokens.filter { it.first == "B" }
            require(a.size == 1 && b.size == 1) { "$name-grades.txt: $id needs one grade after A= and one after B=: $line" }
            letterGrades[id] = a.single().second to b.single().second
        }
        val grades = firstIsA.keys.mapNotNull { id ->
            letterGrades[id]?.let { (a, b) -> id to if (firstIsA.getValue(id)) a to b else b to a }
        }.toMap()
        return GradedSheet(name, firstLabel, secondLabel, grades, firstIsA.keys.filter { it !in letterGrades })
    }

    /** Each report's totals, then each question's change from the first report to the second. */
    fun results(sheets: List<GradedSheet>): String = buildString {
        appendLine("# Blind grading: ${sheets.joinToString(", ") { it.name }}")
        appendLine()
        appendLine("| Sheet | Report | Good | Needs work | Wrong |")
        appendLine("|---|---|---|---|---|")
        for (sheet in sheets) {
            val rows = listOf(
                "first: ${sheet.firstLabel}" to sheet.grades.values.map { it.first },
                "second: ${sheet.secondLabel}" to sheet.grades.values.map { it.second }
            )
            for ((report, grades) in rows) {
                appendLine(
                    "| ${sheet.name} | $report | ${EvalCitations.share(grades.count { it == Grade.GOOD }, grades.size)} | " +
                        "${grades.count { it == Grade.NEEDS_WORK }} | ${grades.count { it == Grade.WRONG }} |"
                )
            }
        }
        sheets.filter { it.ungraded.isNotEmpty() }.forEach { appendLine(); appendLine("Not graded in ${it.name}, so left out: ${it.ungraded.joinToString(", ")}") }

        val ids = sheets.flatMap { it.grades.keys }.distinct()
        val tally = mutableMapOf<String, Int>()
        appendLine()
        appendLine("| Question | First report | Second report | Change |")
        appendLine("|---|---|---|---|")
        for (id in ids) {
            val pairs = sheets.map { it.grades[id] }
            val graded = pairs.filterNotNull()
            val better = graded.count { (first, second) -> second > first }
            val worse = graded.count { (first, second) -> second < first }
            val change = when {
                better > 0 && worse > 0 -> "mixed"
                better > 0 -> "better in $better of ${graded.size}"
                worse > 0 -> "worse in $worse of ${graded.size}"
                else -> "same"
            }
            tally.merge(change.substringBefore(" in"), 1, Int::plus)
            fun cell(pick: (Pair<Grade, Grade>) -> Grade) = pairs.joinToString(", ") { it?.let(pick)?.text ?: "–" }
            appendLine("| $id | ${cell { it.first }} | ${cell { it.second }} | $change |")
        }
        appendLine()
        appendLine(
            "Questions where the second report is better: ${tally["better"] ?: 0}; worse: ${tally["worse"] ?: 0}; " +
                "mixed: ${tally["mixed"] ?: 0}; the same: ${tally["same"] ?: 0}."
        )
    }

    /** [raw] as given (absolute or from the repo [root]), or else a file in the reports [folder]. */
    fun resolveReport(raw: String, root: File, folder: File): File {
        val given = File(raw).let { if (it.isAbsolute) it else File(root, raw) }
        if (given.isFile) return given
        val inFolder = File(folder, raw)
        require(inFolder.isFile) { "No report $raw: looked in the repo root and in $folder" }
        return inFolder
    }
}

/**
 * `./gradlew :server:aiEvalBlind` and `:server:aiEvalBlindScore` (#321). Local files only: no
 * network, no neurons. Arguments: `sheet <repo root> <folder> <first> <second> <name> [seed]`,
 * or `score <folder> <name,name,…>`.
 */
fun main(args: Array<String>) {
    try {
        when (args.firstOrNull()) {
            "sheet" -> writeSheet(args.drop(1))
            "score" -> writeResults(args.drop(1))
            else -> throw IllegalArgumentException("Usage: BlindGrading sheet|score …")
        }
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
    }
}

internal fun writeSheet(args: List<String>) {
    val (root, folder, firstPath, secondPath) = args
    val name = args.getOrNull(4)?.takeIf { it.isNotBlank() } ?: "blind"
    require(firstPath.isNotBlank() && secondPath.isNotBlank()) {
        "Give the two reports: -Pfirst=… -Psecond=… (paths from the repo root, or names in $folder)"
    }
    val seed = args.getOrNull(5)?.takeIf { it.isNotBlank() }?.let { requireNotNull(it.toLongOrNull()) { "-Pseed must be a whole number" } }
        ?: Random.nextLong()
    val files = listOf(firstPath, secondPath).map { BlindGrading.resolveReport(it, File(root), File(folder)) }
    val labels = files.map { it.nameWithoutExtension }.let { (a, b) -> if (a == b) listOf("$a (first)", "$b (second)") else listOf(a, b) }
    val (first, second) = files.zip(labels) { file, label -> LabeledReport(label, BlindGrading.parseReport(file.readText())) }

    val out = File(folder).apply { mkdirs() }
    val targets = listOf("$name.md", "$name-key.txt", "$name-grades.txt").map { File(out, it) }
    // Never over grades already written: a second sheet needs its own name.
    val taken = targets.filter { it.exists() }
    require(taken.isEmpty()) { "${taken.joinToString { it.name }} already exists in $out: pick another -Psheet=…" }

    val blind = BlindGrading.sheet(first, second, seed, name)
    targets.zip(listOf(blind.sheet, blind.key, blind.grades)).forEach { (file, text) -> file.writeText(text) }
    blind.skipped.forEach { println("Left out $it") }
    BlindGrading.differingQuestions(first, second).takeIf { it.isNotEmpty() }?.let {
        println("The question lines differ between the reports for ${it.joinToString(", ")}; the sheet shows the first report's.")
    }
    println("Sheet: ${targets[0].absolutePath}")
    println("Grade in ${targets[2].name}; open ${targets[1].name} only after every answer is graded.")
}

internal fun writeResults(args: List<String>) {
    val folder = File(args[0])
    val names = args.getOrNull(1).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    require(names.isNotEmpty()) { "Give the graded sheets: -Psheets=blind1,blind2" }
    val sheets = names.map { name ->
        val key = File(folder, "$name-key.txt")
        val grades = File(folder, "$name-grades.txt")
        require(key.isFile && grades.isFile) { "$name needs $name-key.txt and $name-grades.txt in $folder" }
        BlindGrading.score(name, key.readText(), grades.readText())
    }
    val text = BlindGrading.results(sheets)
    val file = File(folder, "${names.joinToString("+")}-results.md")
    file.writeText(text)
    println(text)
    println("Results: ${file.absolutePath}")
}
