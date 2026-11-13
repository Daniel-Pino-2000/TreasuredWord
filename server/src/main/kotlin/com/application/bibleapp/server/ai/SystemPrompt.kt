package com.application.bibleapp.server.ai

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
     * v13 is v5 plus one verse of comfort from a fixed list in crisis answers, which the app shows
     * in the help card (#282). Its number skips the v6-v12 experiments, so reports can't mix them up.
     * v14 fixes what v13's two runs (2026-11-13) kept missing: v12's doctrine.md (alcohol, the
     * rapture views, abuse, believer's baptism, positions stated as settled), the rapture and
     * KJV-only views among the open questions, no "some churches differ" ending, a pastor's usual
     * points (grief, repeated sin, safety at home, the Lord's Prayer, Psalm 119:105), "it isn't
     * your fault", no [[CRISIS]] or emergency line outside a crisis, and no verse for medical points.
     */
    const val PROMPT_VERSION = "14"

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

    /**
     * The instructions for an answer in [languageTag], one of [languageNames]' keys. With a
     * [passageReference] and [passageText] (#132), `passage.md` follows them: the passage the
     * question is about, quoted so the model reads it as Scripture, not as instructions.
     */
    fun forLanguage(languageTag: String, passageReference: String? = null, passageText: String? = null): String {
        val language = requireNotNull(languageNames[languageTag]) { "Unsupported language tag $languageTag" }
        val instructions = template.replace("{language}", language)
        if (passageReference == null || passageText == null) return instructions
        // The text can't close the quotes it sits in, or write markers of its own (a [[CRISIS]]).
        val quoted = passageText.trim()
            .replace("\"\"\"", "\"")
            .replace("[[", "[")
            .replace("]]", "]")
        // The text goes in last, so nothing inside it can be taken for a placeholder.
        val passage = passageTemplate.replace("{reference}", passageReference).replace("{text}", quoted)
        return "$instructions\n\n$passage"
    }

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
