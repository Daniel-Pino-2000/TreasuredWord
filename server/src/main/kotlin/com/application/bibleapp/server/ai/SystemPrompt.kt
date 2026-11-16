package com.application.bibleapp.server.ai

import com.application.bibleapp.server.search.FoundVerse

/**
 * The Bible Helper's fixed instructions (#124): `resources/ai/system_prompt.md` with the approved
 * doctrine summary (#121), the answer's language and the book codes filled in.
 *
 * HTML comments (`<!-- … -->`) are notes for developers and never reach the model. Both files
 * are read once, when the class loads, so a missing file fails at startup rather than on the
 * first question.
 */
object SystemPrompt {

    /**
     * Sent back with every answer and stored with reports, so a report says which instructions it
     * was about. Bumped for any change that changes answers: the instructions, doctrine.md, or the
     * provider's temperature. v5 (v4's instructions at temperature 0.2, #206) passed one run; v6-v9
     * tried other answer shapes and scored lower; v10 is v5 with only the framing changed (the Bible
     * first, Baptists named only when asked, longer answers when needed) and firmer doctrine.md (#236).
     * v11 is v10 back at about 250 words: no answer in v5 or v10 needed more (longest 260 and 281).
     * Graded strictly, v10 and v11 gave Wrong answers in every run and v5 in none of three, so v12
     * is v5's instructions with v10's doctrine.md, and alcohol off the list of open questions. v12
     * gave one Wrong answer in two runs, so the developer chose to ship v5 unchanged (2026-11-10):
     * the files are af7f9e8's again, under v5's number, because answers are the ones v5's runs measured.
     */
    const val PROMPT_VERSION = "5"

    /**
     * v5 with the verses search by meaning found for the question (`verses.md`, #287 phase 5).
     * An answer reports this version only when verses were sent; without them (the setting off,
     * search failing, or a passage question) the instructions are v5's exactly, and so is the
     * version. 13 and 14 were instructions that were tried and not shipped (#290).
     */
    const val VERSES_PROMPT_VERSION = "15"

    /** The app's language tags (`AppLanguage.supportedTags`) and the English name the model is told to answer in. */
    val languageNames: Map<String, String> = mapOf(
        "en" to "English",
        "de" to "German",
        "es" to "Spanish",
        "fr" to "French",
        "pt-BR" to "Brazilian Portuguese",
        "id" to "Indonesian",
        "fil" to "Filipino",
        "sw" to "Swahili",
        "ko" to "Korean",
        "zh-Hans" to "Simplified Chinese"
    )

    private val htmlComment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    private val template: String = load("ai/system_prompt.md")
        .replace("{doctrine}", load("ai/doctrine.md"))
        .replace("{bookCodes}", UsfmBookCodes.codes.joinToString(", "))

    private val passageTemplate: String = load("ai/passage.md")

    private val versesTemplate: String = load("ai/verses.md")

    /**
     * The instructions for an answer in [languageTag], one of [languageNames]' keys. With a
     * [passageReference] and [passageText] (#132), `passage.md` follows them: the passage the
     * question is about, quoted so the model reads it as Scripture, not as instructions.
     */
    fun forLanguage(languageTag: String, passageReference: String? = null, passageText: String? = null): String {
        val language = requireNotNull(languageNames[languageTag]) { "Unsupported language tag $languageTag" }
        val instructions = template.replace("{language}", language)
        if (passageReference == null || passageText == null) return instructions
        // The text goes in last, so nothing inside it can be taken for a placeholder.
        val passage = passageTemplate.replace("{reference}", passageReference).replace("{text}", quoted(passageText))
        return "$instructions\n\n$passage"
    }

    /**
     * The instructions with [verses] search found for the question (#287, phase 5), or v5's
     * exactly when there are none. Neighbouring verses become one passage ("MAT 6:33-34"), so the
     * model sees and cites them as a range; a passage keeps the best rank among its verses.
     */
    fun withVerses(languageTag: String, verses: List<FoundVerse>): String {
        val instructions = forLanguage(languageTag)
        if (verses.isEmpty()) return instructions
        val lines = passagesOf(verses).joinToString("\n") { (reference, text) -> "$reference — ${quoted(text)}" }
        return "$instructions\n\n${versesTemplate.replace("{verses}", lines)}"
    }

    /** The version of [withVerses]' instructions: [VERSES_PROMPT_VERSION] with verses, else [PROMPT_VERSION]. */
    fun versionFor(verses: List<FoundVerse>): String = if (verses.isEmpty()) PROMPT_VERSION else VERSES_PROMPT_VERSION

    /** [verses], best first, as (USFM reference, text) passages with neighbours joined. */
    internal fun passagesOf(verses: List<FoundVerse>): List<Pair<String, String>> {
        val rank = verses.withIndex().associate { (index, verse) -> verse to index }
        val runs = mutableListOf<MutableList<FoundVerse>>()
        for (verse in verses.distinct().sortedWith(compareBy({ it.book }, { it.chapter }, { it.verse }))) {
            val last = runs.lastOrNull()?.last()
            if (last != null && last.book == verse.book && last.chapter == verse.chapter && last.verse + 1 == verse.verse) {
                runs.last() += verse
            } else {
                runs += mutableListOf(verse)
            }
        }
        return runs.sortedBy { run -> run.minOf { rank.getValue(it) } }.map { run ->
            val first = run.first()
            val range = if (run.size == 1) "${first.verse}" else "${first.verse}-${run.last().verse}"
            "${UsfmBookCodes.codes[first.book - 1]} ${first.chapter}:$range" to run.joinToString(" ") { it.text.trim() }
        }
    }

    /** Scripture text that can't close the quotes it sits in, or write markers of its own (a [[CRISIS]]). */
    private fun quoted(text: String): String = text.trim()
        .replace("\"\"\"", "\"")
        .replace("[[", "[")
        .replace("]]", "]")

    private fun load(path: String): String {
        val stream = SystemPrompt::class.java.classLoader.getResourceAsStream(path)
            ?: error("Missing resource $path")
        return stream.use { it.readBytes().decodeToString() }
            .replace("\r\n", "\n")
            .replace(htmlComment, "")
            // A removed comment can leave a run of blank lines behind.
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
