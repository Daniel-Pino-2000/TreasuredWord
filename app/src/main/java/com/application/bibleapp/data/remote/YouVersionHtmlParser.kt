package com.application.bibleapp.data.remote

import com.application.bibleapp.data.model.Footnote
import com.application.bibleapp.data.model.StoredHeading
import com.application.bibleapp.data.model.StoredVerseContent
import com.application.bibleapp.data.model.VerseRun

/**
 * Turns one chapter of YouVersion's `format=html` passage (#366) into the same verses and
 * footnotes a bible.helloao.org download produces, so the reader renders both alike.
 *
 * The markup is USFM turned into classes (checked 2026-11-21 on LBLA, NBLA, NASB 2020 and AMP):
 * `div.p` paragraphs, `div.q`/`q2` poetry lines, `div.s|ms|qa` + `yv-h` headings, `div.d` a
 * psalm's Hebrew title, `span.yv-v[v=N]` where verse N starts (`span.yv-vlbl` is its printed
 * number), `span.wj` words of Jesus, `span.sc` small caps (LORD), and `span.yv-n` a footnote
 * whose `span.ft` is its text (`span.fr` repeats the reference). A verse runs on across divs
 * until the next verse marker. The text must be shown unaltered (every license), so this only
 * drops the markup, the printed verse numbers and footnote references.
 *
 * Pure Kotlin with no Android dependency, so it's unit tested on the JVM.
 */
object YouVersionHtmlParser {

    data class Chapter(val verses: List<ParsedVerse>, val footnotes: List<Footnote>)

    private val TOKEN = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>|([^<]+)""")
    private val CLASS_ATTR = Regex("""class\s*=\s*"([^"]*)"""")
    private val VERSE_ATTR = Regex("""\bv\s*=\s*"(\d+)"""")
    private val POETRY_CLASS = Regex("""^q(\d)?$""")
    private val WHITESPACE = Regex("""\s+""")
    private val CHAPTER_LABEL = Regex("""^\p{L}+ \d+$""")
    private const val PILCROW = '¶'

    private class Frame(val tag: String, val classes: Set<String>)

    fun parse(html: String): Chapter = Builder().apply {
        TOKEN.findAll(html).forEach { match ->
            val text = match.groups[4]?.value
            when {
                text != null -> onText(decodeEntities(text))
                match.groupValues[1] == "/" -> onClose()
                else -> onOpen(match.groupValues[2].lowercase(), match.groupValues[3])
            }
        }
    }.finish()

    private class Builder {
        private val stack = ArrayDeque<Frame>()
        private val verses = mutableListOf<ParsedVerse>()
        private val footnotes = mutableListOf<Footnote>()

        private var verse: Int? = null
        private var runs = mutableListOf<VerseRun>()
        private var headingsForVerse = listOf<StoredHeading>()
        private val pendingHeadings = mutableListOf<StoredHeading>()

        private var pendingParagraph = false
        private var pendingPoemLevel: Int? = null
        // True while still on the poetry line a run already started, so a run split off by a
        // footnote marker stays on that line instead of starting a new one.
        private var onPoemLine = false

        private var headingType: String? = null
        private var chapterLabelHeading = false
        private val headingText = StringBuilder()
        private var footnoteDepth = -1
        private val footnoteText = StringBuilder()
        private var nextNoteId = 0

        fun onOpen(tag: String, attributes: String) {
            val classes = CLASS_ATTR.find(attributes)?.groupValues?.get(1)
                ?.split(' ')?.filter { it.isNotBlank() }?.toSet().orEmpty()
            stack.addLast(Frame(tag, classes))
            when {
                "yv-v" in classes -> VERSE_ATTR.find(attributes)?.groupValues?.get(1)?.toIntOrNull()?.let(::startVerse)
                "yv-n" in classes -> if (footnoteDepth < 0) {
                    footnoteDepth = stack.size
                    footnoteText.clear()
                }
                tag != "div" -> {}
                "yv-h" in classes -> {
                    startHeading("heading")
                    chapterLabelHeading = classes.any { it.startsWith("ms") }
                }
                "d" in classes -> startHeading("hebrew_subtitle")
                classes.any { POETRY_CLASS.matches(it) } -> {
                    pendingPoemLevel = classes.firstNotNullOf { POETRY_CLASS.matchEntire(it) }
                        .groupValues[1].toIntOrNull() ?: 1
                    onPoemLine = false
                }
                classes.isNotEmpty() -> {
                    pendingParagraph = true
                    pendingPoemLevel = null
                    onPoemLine = false
                }
            }
        }

        fun onClose() {
            val frame = stack.removeLastOrNull() ?: return
            if (stack.size + 1 == footnoteDepth) {
                footnoteDepth = -1
                closeFootnote()
            } else if (frame.tag == "div" && headingType != null && isHeadingFrame(frame)) {
                closeHeading()
            }
        }

        fun onText(raw: String) {
            if (stack.any { "yv-vlbl" in it.classes || "fr" in it.classes }) return
            if (footnoteDepth >= 0) {
                if (stack.any { "ft" in it.classes }) footnoteText.append(raw)
                return
            }
            val text = if (stack.any { "sc" in it.classes }) raw.uppercase() else raw
            if (headingType != null) {
                headingText.append(text)
                return
            }
            if (verse == null) return
            // NASB marks paragraphs inside poetry with a pilcrow in the text ("¶But You, LORD");
            // like bible.helloao.org's, it becomes a paragraph break rather than a stray glyph.
            val wordsOfJesus = stack.any { "wj" in it.classes }
            text.replace(WHITESPACE, " ").split(PILCROW).forEachIndexed { index, piece ->
                if (index > 0) pendingParagraph = true
                appendVerseText(if (index > 0) piece.trimStart() else piece, wordsOfJesus)
            }
        }

        private fun appendVerseText(text: String, wordsOfJesus: Boolean) {
            val last = runs.lastOrNull()
            val startsLine = pendingPoemLevel != null || pendingParagraph
            if (text.isBlank()) {
                if (last != null && !startsLine && !last.text.endsWith(" ")) runs[runs.lastIndex] = last.copy(text = last.text + " ")
                return
            }
            val continues = last != null && !startsLine && last.footnoteId == null && last.isWordsOfJesus == wordsOfJesus
            if (continues) {
                runs[runs.lastIndex] = last!!.copy(text = last.text + text)
                return
            }
            val poemLevel = pendingPoemLevel
            runs += VerseRun(
                text = text.trimStart(),
                isWordsOfJesus = wordsOfJesus,
                poemLevel = poemLevel,
                lineBreakBefore = pendingParagraph && runs.isNotEmpty(),
                paragraphBreakBefore = pendingParagraph && runs.isEmpty(),
                sameLine = poemLevel == null && onPoemLine
            )
            if (poemLevel != null) onPoemLine = true
            pendingParagraph = false
            pendingPoemLevel = null
        }

        private fun startVerse(number: Int) {
            finishVerse()
            verse = number
            headingsForVerse = pendingHeadings.toList()
            pendingHeadings.clear()
        }

        private fun finishVerse() {
            val number = verse ?: return
            val finished = runs.map { it.copy(text = it.text.trim()) }
                .filter { it.text.isNotEmpty() || it.footnoteId != null }
            val plain = finished.joinToString(" ") { it.text }.replace(WHITESPACE, " ").trim()
            if (plain.isNotEmpty()) {
                verses += ParsedVerse(number, plain, StoredVerseContent(headings = headingsForVerse, runs = finished))
            }
            runs = mutableListOf()
            headingsForVerse = emptyList()
        }

        private fun startHeading(type: String) {
            headingType = type
            headingText.clear()
        }

        private fun isHeadingFrame(frame: Frame) = "yv-h" in frame.classes || "d" in frame.classes

        private fun closeHeading() {
            val text = headingText.toString().replace(WHITESPACE, " ").trim()
            // NASB's "Psalm 3" major title repeats the reader's own chapter title; "Book 1" stays.
            val repeatsChapterTitle = chapterLabelHeading && CHAPTER_LABEL.matches(text)
            if (text.isNotEmpty() && !repeatsChapterTitle) pendingHeadings += StoredHeading(type = headingType!!, text = text)
            headingType = null
            chapterLabelHeading = false
            headingText.clear()
        }

        private fun closeFootnote() {
            val text = footnoteText.toString().replace(WHITESPACE, " ").trim()
            footnoteText.clear()
            if (text.isEmpty()) return
            val noteId = nextNoteId++
            // A note inside a heading (a psalm title's) belongs to the verse that follows it; it has
            // no marker of its own, so the reader lists it with that verse's notes.
            val target = if (headingType != null) (verse ?: 0) + 1 else verse ?: 1
            footnotes += Footnote(noteId = noteId, verse = target, caller = null, text = text)
            if (headingType != null || verse == null) return
            val last = runs.lastOrNull()
            if (last != null && last.footnoteId == null) {
                runs[runs.lastIndex] = last.copy(footnoteId = noteId, text = last.text.trimEnd())
            } else {
                // A note before any of the verse's words (NASB's Psalm 3:8): its marker opens the
                // line the words then continue, instead of sitting on a line of its own.
                val poemLevel = pendingPoemLevel
                runs += VerseRun(
                    text = "",
                    footnoteId = noteId,
                    poemLevel = poemLevel,
                    paragraphBreakBefore = pendingParagraph && runs.isEmpty(),
                    sameLine = poemLevel == null && onPoemLine
                )
                if (poemLevel != null) onPoemLine = true
                pendingPoemLevel = null
                pendingParagraph = false
            }
        }

        fun finish(): Chapter {
            if (headingType != null) closeHeading()
            finishVerse()
            return Chapter(verses, footnotes)
        }
    }

    private fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        return text
            .replace(Regex("""&#(\d+);""")) { it.groupValues[1].toIntOrNull()?.let { code -> String(Character.toChars(code)) } ?: it.value }
            .replace(Regex("""&#x([0-9a-fA-F]+);""")) { it.groupValues[1].toIntOrNull(16)?.let { code -> String(Character.toChars(code)) } ?: it.value }
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }
}
