package com.application.bibleapp.server.search.eval

/** One query of `docs/semantic_search_eval.md` (#287): a good result has one of [expected] in its top 10. */
data class SearchEvalQuery(
    val id: String,
    val query: String,
    val language: String = "en",
    val expected: Set<VerseRef>
) {
    /** TOPIC, MOD, KJV or LANG: what the report adds up separately. */
    val group: String get() = id.substringBefore('-')
}

/**
 * Reads `docs/semantic_search_eval.md` (#287) like the Bible Helper's questions file: every `###`
 * heading after "## Queries" is one, with `- **Query:**`, `- **Language:**` and `- **Expect:**`
 * lines. A heading without a Query or Expect line, or a reference that doesn't parse, is an error,
 * so a typo can't quietly drop a query or a verse.
 */
object SearchEvalQueries {

    private val field = Regex("""^- \*\*(Query|Language|Expect):\*\*\s*(.*)$""")

    fun parse(markdown: String): List<SearchEvalQuery> {
        val lines = markdown.replace("\r\n", "\n").lines()
        val start = lines.indexOfFirst { it.trim() == "## Queries" }
        require(start >= 0) { "No \"## Queries\" heading" }

        val queries = mutableListOf<SearchEvalQuery>()
        var id: String? = null
        var fields = mutableMapOf<String, String>()
        fun finish() {
            val current = id ?: return
            val query = requireNotNull(fields["Query"]?.takeIf { it.isNotBlank() }) { "$current has no Query line" }
            val expect = requireNotNull(fields["Expect"]?.takeIf { it.isNotBlank() }) { "$current has no Expect line" }
            queries += SearchEvalQuery(
                id = current,
                query = query,
                language = fields["Language"]?.trim('`', ' ')?.takeIf { it.isNotEmpty() } ?: "en",
                expected = expect.split(',').flatMap { VerseRef.parseRange(it) }.toSet()
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
        val duplicates = queries.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate query IDs: $duplicates" }
        return queries
    }
}
