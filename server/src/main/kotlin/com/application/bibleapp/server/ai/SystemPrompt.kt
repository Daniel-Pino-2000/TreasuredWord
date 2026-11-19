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
     */
    const val PROMPT_VERSION = "5"

    /**
     * An answer whose instructions were [PROMPT_VERSION]'s plus section 9, the topic guides the
     * question matched (#319, [withGuides]). Questions with no guide report [PROMPT_VERSION], since
     * their instructions are exactly v5's.
     */
    const val GUIDES_VERSION = "16"

    /**
     * An answer whose instructions held only the doctrine items its question needs (#297,
     * [DoctrineRouter]) instead of all of `doctrine.md`, with the topic guides it matched, if any.
     * A question that couldn't be matched gets the whole doctrine and reports v5 or v16 as before.
     */
    const val DOCTRINE_VERSION = "17"

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

    /** `doctrine.md` in its numbered items (#297). Its whole [Doctrine.text] is the file as read. */
    val doctrine: Doctrine = load("ai/doctrine.md").let { whole ->
        Doctrine.parse(whole).also { check(it.text() == whole) { "doctrine.md doesn't split into its items cleanly" } }
    }

    /** The instructions with `{doctrine}` still open, for a question sent only some items (#297). */
    private val withoutDoctrine: String = load("ai/system_prompt.md")
        .replace("{bookCodes}", UsfmBookCodes.codes.joinToString(", "))

    private val template: String = withoutDoctrine.replace("{doctrine}", doctrine.text())

    private val passageTemplate: String = load("ai/passage.md")

    /**
     * The instructions for an answer in [languageTag], one of [languageNames]' keys. With a
     * [passageReference] and [passageText] (#132), `passage.md` follows them: the passage the
     * question is about, quoted so the model reads it as Scripture, not as instructions. With
     * [doctrineItems] (#297), section 2 holds only those items of `doctrine.md`; null is all of it.
     */
    fun forLanguage(
        languageTag: String,
        passageReference: String? = null,
        passageText: String? = null,
        doctrineItems: List<Int>? = null
    ): String {
        val language = requireNotNull(languageNames[languageTag]) { "Unsupported language tag $languageTag" }
        val filled = if (doctrineItems == null) template else withoutDoctrine.replace("{doctrine}", doctrine.text(doctrineItems))
        val instructions = filled.replace("{language}", language)
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

    /**
     * The instructions for a question without a passage that matched [guides] (#319): v5's,
     * then section 9 with the guides; v5's exactly when there are none. With [doctrineItems]
     * (#297), only those doctrine items.
     */
    fun withGuides(languageTag: String, guides: List<RoutedGuide>, doctrineItems: List<Int>? = null): String =
        forLanguage(languageTag, doctrineItems = doctrineItems) + (TopicGuides.section(guides)?.let { "\n\n$it" } ?: "")

    /** The version an answer with [guides] and [doctrineItems] reports and is stored under. */
    fun versionFor(guides: List<RoutedGuide>, doctrineItems: List<Int>? = null): String = when {
        doctrineItems != null -> DOCTRINE_VERSION
        guides.isEmpty() -> PROMPT_VERSION
        else -> GUIDES_VERSION
    }

    /** [withGuides] for what [TopicGuideMatcher.matchQuestion] found. */
    fun withMatch(languageTag: String, match: QuestionMatch): String = withGuides(languageTag, match.guides, match.doctrineItems)

    /** [versionFor] for what [TopicGuideMatcher.matchQuestion] found. */
    fun versionFor(match: QuestionMatch): String = versionFor(match.guides, match.doctrineItems)

    internal fun load(path: String): String {
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
