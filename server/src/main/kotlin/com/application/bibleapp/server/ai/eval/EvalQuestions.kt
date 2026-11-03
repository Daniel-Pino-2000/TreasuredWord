package com.application.bibleapp.server.ai.eval

/** Whether an evaluation question's answer must start with `[[CRISIS]]`, must not contain it, or either. */
enum class CrisisExpectation { YES, NO, EITHER }

/** One question of `docs/ai_helper_eval.md` (#121), as the runner reads it. */
data class EvalQuestion(
    val id: String,
    val ask: String,
    val language: String = "en",
    val crisis: CrisisExpectation = CrisisExpectation.NO,
    val must: String? = null,
    val mustNot: String? = null
)

/**
 * Reads the questions of `docs/ai_helper_eval.md` (#126): every `###` heading after
 * "## Questions" is one, with `- **Ask:**`, `- **Language:**`, `- **Crisis:**`, `- **Must:**` and
 * `- **Must not:**` lines (the file's "How a question is written" section). A heading without an
 * Ask line is an error, so a typo can't quietly drop a question.
 */
object EvalQuestions {

    private val field = Regex("""^- \*\*(Ask|Language|Crisis|Must|Must not):\*\*\s*(.*)$""")

    fun parse(markdown: String): List<EvalQuestion> {
        val lines = markdown.replace("\r\n", "\n").lines()
        val start = lines.indexOfFirst { it.trim() == "## Questions" }
        require(start >= 0) { "No \"## Questions\" heading" }

        val questions = mutableListOf<EvalQuestion>()
        var id: String? = null
        var fields = mutableMapOf<String, String>()
        fun finish() {
            val current = id ?: return
            val ask = requireNotNull(fields["Ask"]) { "$current has no Ask line" }
            val crisis = when (fields["Crisis"]?.trim('`', ' ')?.lowercase()) {
                null, "no" -> CrisisExpectation.NO
                "yes" -> CrisisExpectation.YES
                "either" -> CrisisExpectation.EITHER
                else -> throw IllegalArgumentException("$current has an unknown Crisis value: ${fields["Crisis"]}")
            }
            questions += EvalQuestion(
                id = current,
                ask = ask,
                language = fields["Language"]?.trim('`', ' ')?.takeIf { it.isNotEmpty() } ?: "en",
                crisis = crisis,
                must = fields["Must"],
                mustNot = fields["Must not"]
            )
        }
        for (line in lines.drop(start + 1)) {
            when {
                line.startsWith("### ") -> {
                    finish()
                    id = line.removePrefix("### ").trim()
                    fields = mutableMapOf()
                }
                line.startsWith("## ") -> {
                    finish()
                    id = null
                }
                else -> field.find(line.trim())?.let { match -> fields[match.groupValues[1]] = match.groupValues[2].trim() }
            }
        }
        finish()
        val duplicates = questions.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate question IDs: $duplicates" }
        return questions
    }
}
