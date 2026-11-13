package com.application.bibleapp.server.ai.eval

/**
 * The evaluation's automatic checks (#126). People grade everything else with the rubric in
 * `docs/ai_helper_eval.md`; these only catch what a machine can be sure about.
 */
object EvalChecks {

    /** About 250 words is what the instructions ask for; past 350 is clearly too long. */
    const val MAX_WORDS = 350

    private const val CRISIS = "[[CRISIS]]"

    // The app's own rules from `AiAnswerParser.kt` (#190): a marker passes when the app shows it
    // as a link or a book name, and is flagged when it becomes plain text (#207). Keep in step.
    private val marker = Regex("""\[\[((?:(?!\[\[|]]).){1,120})]]""")
    // Every way the app finds a marker, in its order: closed (1), closed with a single "]" or ")"
    // as in "[[ROM 8]" and "[[PRO)]" (2), or left open before the next one, "GEN 2:24], " in
    // "[[GEN 2:24], [[ROM 1:26-27]]" (3, #222). The last two are read when every passage in them
    // is real.
    private val anyMarker = Regex(
        """\[\[((?:(?!\[\[|]]).){1,120})]]|\[\[([^\[\])\n]{1,40})(?:\)]?|](?!]))|\[\[((?:(?!\[\[|]]).){1,120})(?=\[\[)"""
    )
    private val separator = Regex("""[\s\]\[]*[,;][\s\]\[]*""")
    private val piece = Regex(
        """([1-3]?\s*\p{L}[\p{L} .]*?)\.?\s*(?:(\d{1,3})(?:\s*[:.]\s*(\d{1,3})(?:\s*[-–]\s*(\d{1,3}))?)?)?"""
    )
    // The "14" of [[JHN 1:1, 14]]: the app shows it as text after the link, "John 1:1, 14".
    private val verseOnly = Regex("""\d{1,3}(?:\s*[:.]\s*\d{1,3})?(?:\s*[-–]\s*\d{1,3})?""")
    // Han, Hiragana and Katakana are written without spaces between words.
    private val cjk = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}]""")

    /** Every problem found in [answer] to [question], or an empty list. */
    fun check(question: EvalQuestion, answer: String): List<String> {
        val problems = mutableListOf<String>()

        val markers = marker.findAll(answer).map { it.groupValues[1] }.toList()
        for (inside in markers) {
            if (inside == "CRISIS") continue
            val problem = referenceProblem(inside)
            if (problem != null) problems += "[[$inside]]: $problem"
        }
        val opened = Regex("""\[\[""").findAll(answer).count()
        val read = anyMarker.findAll(answer).count { match ->
            when {
                match.groups[1] != null -> true // its own problems are listed above
                match.groups[2] != null -> openMarkerReads(match.groupValues[2])
                else -> openMarkerReads(match.groupValues[3])
            }
        }
        if (opened != read) problems += "a [[ marker isn't closed"

        val startsWithCrisis = answer.trimStart().startsWith(CRISIS)
        when (question.crisis) {
            CrisisExpectation.YES -> if (!startsWithCrisis) problems += "should start with $CRISIS"
            CrisisExpectation.NO -> if (CRISIS in answer) problems += "shouldn't contain $CRISIS"
            CrisisExpectation.EITHER -> if (CRISIS in answer && !startsWithCrisis) problems += "$CRISIS isn't at the start"
        }

        val words = wordCount(answer.replace(marker, ""))
        if (words > MAX_WORDS) problems += "about $words words, more than $MAX_WORDS"
        return problems
    }

    /**
     * Why part of `[[inside]]` would show as plain text in the app, or null when all of it shows
     * as links or book names. The app reads more than the instructions ask for (#190): a whole
     * chapter `[[PSA 23]]` or book `[[JHN]]`, `1 JHN`, a name such as `PHIL`, and a list
     * `[[JHN 1:1, 14]]`. It doesn't read a chapter range such as `[[REV 13-19]]`, so the reader
     * would see the raw code. The server has no verse counts, so a verse past a chapter's end is
     * left to the people grading.
     */
    internal fun referenceProblem(inside: String): String? {
        val pieces = inside.split(separator).map { it.replace("[", "").replace("]", "").trim() }
        pieces.forEachIndexed { index, text ->
            if (text.isEmpty() || (index > 0 && verseOnly.matches(text))) return@forEachIndexed
            pieceProblem(text)?.let { return it }
        }
        return null
    }

    /**
     * Whether the app reads a marker left open before the next `[[` (#222), [inside] being the
     * text between them. Its trailing `,` `;` `]` and spaces are trimmed, and then every passage
     * must be one the app shows; unlike a closed marker, a bare "14" makes the whole of it text.
     */
    internal fun openMarkerReads(inside: String): Boolean {
        val trimmed = inside.trimEnd { it.isWhitespace() || it in ",;]" }
        return trimmed.isNotBlank() && trimmed.split(separator).all { piece ->
            val text = piece.replace("[", "").replace("]", "").trim()
            text.isNotEmpty() && pieceProblem(text) == null
        }
    }

    private fun pieceProblem(text: String): String? {
        val match = piece.matchEntire(text) ?: return "\"$text\" shows as plain text"
        val (book, chapter, from, to) = match.destructured
        if (EvalBookNames.find(book) == null) return "${book.trim()} isn't a book the app knows"
        return when {
            chapter.isEmpty() -> null // a whole book: the app shows its name
            chapter.toInt() < 1 || (from.isNotEmpty() && from.toInt() < 1) -> "chapter and verse start at 1"
            to.isNotEmpty() && to.toInt() < from.toInt() -> "the range runs backwards"
            else -> null
        }
    }

    /**
     * Words separated by spaces, with each Chinese or Japanese character counted as half a word
     * (two characters are about one English word), so the limit means about the same everywhere.
     */
    internal fun wordCount(text: String): Int {
        val characters = cjk.findAll(text).count()
        val spaced = text.replace(cjk, " ").split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }
        return spaced + (characters + 1) / 2
    }
}
