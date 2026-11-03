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

    /** Sent back with every answer and stored with reports, so a report says which instructions it was about. */
    const val PROMPT_VERSION = "1"

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
