package com.application.bibleapp.data.remote

import com.application.bibleapp.data.model.Footnote
import com.application.bibleapp.data.model.StoredHeading
import com.application.bibleapp.data.model.StoredVerseContent
import com.application.bibleapp.data.model.VerseRun

/**
 * Turns one chapter of Crossway's ESV API HTML (`/v3/passage/html/`, #377) into the same verses
 * and footnotes the other sources produce, so the reader renders them alike.
 *
 * The markup: `p` paragraphs; inside poetry, `span.line` lines (`span.indent line` indented once,
 * `indent-2` twice); `h3` headings, `h4.psalm-title` a psalm's title; `b.chapter-num` /
 * `b.verse-num` where a verse starts, with the verse's id (`v43003016-1`, BBCCCVVV) and its printed
 * number; `span.divine-name` the small-caps LORD; `span.woc` words of Christ; `sup.footnote` a
 * footnote's marker, whose text is in the `div.footnotes` block after the chapter (`note.footnote`,
 * after a `span.footnote-ref` "3:16"). Anything marked `extra_text` (the reference, the audio link)
 * is skipped. The text must be shown unaltered (Crossway: "You may not change any of the words"),
 * so this only drops the markup, the printed numbers and the footnote references.
 *
 * Pure Kotlin with no Android dependency, so it's unit tested on the JVM, on made-up text: ESV
 * text never goes in this public repository.
 */
object EsvHtmlParser {

    data class Chapter(val verses: List<ParsedVerse>, val footnotes: List<Footnote>)

    private val TOKEN = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*?)(/?)>|([^<]+)""")
    private val CLASS_ATTR = Regex("""class\s*=\s*["']([^"']*)["']""")
    private val VERSE_ID = Regex("""id\s*=\s*["']v(\d{2})(\d{3})(\d{3})(?:-\d+)?["']""")
    private val FOOTNOTE_REF = Regex("""^\s*(?:(\d+):)?(\d+)""")
    private val WHITESPACE = Regex("""\s+""")
    private val VOID_TAGS = setOf("br", "hr", "img", "meta", "link", "input")

    private class Frame(val tag: String, val classes: Set<String>)

    // The short copyright, "(ESV)" linked to esv.org: the reader's credit carries the full notice.
    private val SHORT_COPYRIGHT = Regex("""\(\s*<a[^>]*class\s*=\s*["'][^"']*copyright[^"']*["'][^>]*>.*?</a>\s*\)""", RegexOption.DOT_MATCHES_ALL)

    fun parse(html: String): Chapter = Builder().apply {
        TOKEN.findAll(html.replace(SHORT_COPYRIGHT, "")).forEach { match ->
            val text = match.groups[5]?.value
            val tag = match.groupValues[2].lowercase()
            when {
                text != null -> onText(decodeEntities(text))
                match.groupValues[1] == "/" -> onClose(tag)
                else -> onOpen(tag, match.groupValues[3], selfClosing = match.groupValues[4] == "/" || tag in VOID_TAGS)
            }
        }
    }.finish()

    private class Builder {
        private val stack = ArrayDeque<Frame>()
        private val verses = mutableListOf<ParsedVerse>()

        private var verse: Int? = null
        private var runs = mutableListOf<VerseRun>()
        private var headingsForVerse = listOf<StoredHeading>()
        private val pendingHeadings = mutableListOf<StoredHeading>()

        private var pendingParagraph = false
        private var pendingPoemLevel: Int? = null
        private var onPoemLine = false

        private var headingType: String? = null
        private var headingDepth = -1
        private val headingText = StringBuilder()

        // Footnote markers in the text, in order: the verse each sits in. Their texts come later,
        // in the same order, in the footnotes block.
        private val markerVerses = mutableListOf<Int>()
        private val footnoteBodies = mutableListOf<Pair<Int?, String>>()
        private var footnoteBlockDepth = -1
        private var noteBodyDepth = -1
        private var noteRefDepth = -1
        private val noteRef = StringBuilder()
        private val noteText = StringBuilder()
        private var pendingNoteVerse: Int? = null

        private fun inClass(name: String) = stack.any { name in it.classes }

        fun onOpen(tag: String, attributes: String, selfClosing: Boolean) {
            val classes = CLASS_ATTR.find(attributes)?.groupValues?.get(1)
                ?.split(' ')?.filter { it.isNotBlank() }?.toSet().orEmpty()
            if (selfClosing) return
            stack.addLast(Frame(tag, classes))
            val depth = stack.size
            when {
                footnoteBlockDepth >= 0 -> when {
                    "footnote-ref" in classes -> {
                        noteRefDepth = depth
                        noteRef.clear()
                    }
                    tag == "note" -> {
                        noteBodyDepth = depth
                        noteText.clear()
                    }
                }
                "footnotes" in classes -> footnoteBlockDepth = depth
                tag == "b" && ("verse-num" in classes || "chapter-num" in classes) ->
                    VERSE_ID.find(attributes)?.groupValues?.get(3)?.toIntOrNull()?.let(::startVerse)
                tag == "sup" && "footnote" in classes -> addFootnoteMarker()
                tag == "h3" || tag == "h4" -> if ("extra_text" !in classes) {
                    headingType = if ("psalm-title" in classes) "hebrew_subtitle" else "heading"
                    headingDepth = depth
                    headingText.clear()
                }
                tag == "p" -> {
                    pendingParagraph = true
                    pendingPoemLevel = null
                    onPoemLine = false
                }
                tag == "span" && "line" in classes -> {
                    pendingPoemLevel = when {
                        "indent-2" in classes -> 3
                        "indent" in classes -> 2
                        else -> 1
                    }
                    pendingParagraph = false
                    onPoemLine = false
                }
            }
        }

        fun onClose(tag: String) {
            // Tolerates a stray closing tag: pops back to the matching one, if it's open at all.
            if (stack.none { it.tag == tag }) return
            while (stack.isNotEmpty()) {
                val depth = stack.size
                val frame = stack.removeLast()
                when (depth) {
                    noteRefDepth -> noteRefDepth = -1
                    noteBodyDepth -> {
                        noteBodyDepth = -1
                        closeFootnoteBody()
                    }
                    footnoteBlockDepth -> footnoteBlockDepth = -1
                    headingDepth -> closeHeading()
                }
                if (frame.tag == tag) break
            }
        }

        fun onText(raw: String) {
            if (footnoteBlockDepth >= 0) {
                when {
                    noteRefDepth >= 0 -> noteRef.append(raw)
                    noteBodyDepth >= 0 -> noteText.append(raw)
                }
                return
            }
            if (inClass("extra_text") || inClass("verse-num") || inClass("chapter-num") || inClass("footnote") ||
                inClass("crossref")
            ) return
            val text = if (inClass("divine-name")) raw.uppercase() else raw
            if (headingType != null) {
                headingText.append(text)
                return
            }
            if (verse == null) return
            appendVerseText(text.replace(WHITESPACE, " "), wordsOfJesus = inClass("woc"))
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
            if (number == verse) return // the same verse marked again
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

        private fun addFootnoteMarker() {
            val noteId = markerVerses.size
            if (headingType != null || verse == null) {
                // A note in a heading or a psalm's title belongs to the verse that follows it; it has
                // no marker of its own, so the reader lists it with that verse's notes.
                markerVerses += (verse ?: 0) + 1
                return
            }
            markerVerses += verse!!
            val last = runs.lastOrNull()
            if (last != null && last.footnoteId == null) {
                runs[runs.lastIndex] = last.copy(footnoteId = noteId, text = last.text.trimEnd())
            } else {
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

        private fun closeFootnoteBody() {
            val verse = FOOTNOTE_REF.find(noteRef)?.groupValues?.get(2)?.toIntOrNull()
            footnoteBodies += verse to noteText.toString().replace(WHITESPACE, " ").trim()
            noteRef.clear()
            noteText.clear()
        }

        private fun closeHeading() {
            val text = headingText.toString().replace(WHITESPACE, " ").trim()
            if (text.isNotEmpty()) pendingHeadings += StoredHeading(type = headingType!!, text = text)
            headingType = null
            headingDepth = -1
            headingText.clear()
        }

        fun finish(): Chapter {
            if (headingType != null) closeHeading()
            finishVerse()
            val footnotes = footnoteBodies.mapIndexedNotNull { index, (refVerse, text) ->
                if (text.isEmpty()) return@mapIndexedNotNull null
                val verse = markerVerses.getOrNull(index) ?: refVerse ?: return@mapIndexedNotNull null
                Footnote(noteId = index, verse = verse, caller = null, text = text)
            }
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
