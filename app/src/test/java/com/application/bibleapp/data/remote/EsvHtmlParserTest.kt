package com.application.bibleapp.data.remote

import com.application.bibleapp.data.model.Footnote
import com.application.bibleapp.data.model.StoredHeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ESV API's HTML as the reader's verses (#377). Every word here is made up: ESV text never goes
 * in this public repository (docs/bible-licensing.md). Only the markup is Crossway's.
 */
class EsvHtmlParserTest {

    @Test
    fun `verses, a heading and paragraphs`() {
        val html = """
            <h3 id="p40005001_01-1">A made-up heading</h3>
            <p id="p40005001_02-1" class="starts-chapter"><b class="chapter-num" id="v40005001-1">5:1&nbsp;</b>First words here. <b class="verse-num" id="v40005002-1">2&nbsp;</b>Second words.</p>
            <p id="p40005003_02-1"><b class="verse-num" id="v40005003-1">3&nbsp;</b>A new paragraph.</p>
        """.trimIndent()

        val chapter = EsvHtmlParser.parse(html)

        assertEquals(listOf(1, 2, 3), chapter.verses.map { it.number })
        assertEquals(listOf("First words here.", "Second words.", "A new paragraph."), chapter.verses.map { it.plainText })
        assertEquals(listOf(StoredHeading("heading", "A made-up heading")), chapter.verses[0].richContent.headings)
        assertTrue(chapter.verses[2].richContent.runs.first().paragraphBreakBefore)
        assertFalse(chapter.verses[1].richContent.runs.first().paragraphBreakBefore)
    }

    @Test
    fun `poetry lines keep their indentation, and the small-caps name reads LORD`() {
        val html = """
            <h4 id="p19099001_01-1" class="psalm-title">A made-up title.</h4>
            <p class="block-indent"><span class="begin-line-group"></span>
            <span id="p19099001_02-1" class="line"><b class="chapter-num" id="v19099001-1">99:1&nbsp;</b>The <span class="divine-name">Lord</span> made-up line;</span><br />
            <span id="p19099001_02-1" class="indent line">&nbsp;&nbsp;&nbsp;&nbsp;an indented line.</span><br />
            <span id="p19099002_02-1" class="line"><b class="verse-num" id="v19099002-1">2&nbsp;</b>Another line.</span><br />
            <span class="end-line-group"></span>
            </p>
        """.trimIndent()

        val chapter = EsvHtmlParser.parse(html)
        val first = chapter.verses[0]

        assertEquals("The LORD made-up line; an indented line.", first.plainText)
        assertEquals(listOf(1, 2), first.richContent.runs.map { it.poemLevel })
        assertEquals(listOf(StoredHeading("hebrew_subtitle", "A made-up title.")), first.richContent.headings)
        assertEquals(1, chapter.verses[1].richContent.runs.first().poemLevel)
    }

    @Test
    fun `footnote markers point to the notes after the chapter, without their references`() {
        val html = """
            <p><b class="chapter-num" id="v43003001-1">3:1&nbsp;</b>Words with a note<sup class="footnote"><a class="fn" href="#f1-" id="b1-" title="ignored">1</a></sup> and more.
            <b class="verse-num" id="v43003002-1">2&nbsp;</b>Another note here.<sup class="footnote"><a class="fn" href="#f2-" id="b2-">2</a></sup></p>
            <div class="footnotes extra_text"><h3>Footnotes</h3>
            <p><span class="footnote"><a href="#b1-" id="f1-">[1]</a></span> <span class="footnote-ref">3:1</span> <note class="footnote">Or <em>a made-up reading</em></note></p>
            <p><span class="footnote"><a href="#b2-" id="f2-">[2]</a></span> <span class="footnote-ref">3:2</span> <note class="footnote">Greek <em>made up</em></note></p>
            </div>
        """.trimIndent()

        val chapter = EsvHtmlParser.parse(html)

        assertEquals(listOf("Words with a note and more.", "Another note here."), chapter.verses.map { it.plainText })
        assertEquals(0, chapter.verses[0].richContent.runs.first().footnoteId)
        assertEquals(1, chapter.verses[1].richContent.runs.first().footnoteId)
        assertEquals(
            listOf(Footnote(0, 1, null, "Or a made-up reading"), Footnote(1, 2, null, "Greek made up")),
            chapter.footnotes
        )
    }

    @Test
    fun `words of Christ are marked, and extra text is skipped`() {
        val html = """
            <h2 class="extra_text">Made-up 1</h2>
            <p><b class="chapter-num" id="v41001001-1">1:1&nbsp;</b>He said, <span class="woc">“Made-up words.”</span></p>
            <p>(<a href="http://www.esv.org" class="copyright">ESV</a>)</p>
        """.trimIndent()

        val verse = EsvHtmlParser.parse(html).verses.single()

        assertEquals("He said, “Made-up words.”", verse.plainText)
        assertEquals(listOf(false, true), verse.richContent.runs.map { it.isWordsOfJesus })
        assertTrue(verse.richContent.headings.isEmpty())
    }

    @Test
    fun `nothing before the first verse becomes text`() {
        assertTrue(EsvHtmlParser.parse("<p>No verse here.</p>").verses.isEmpty())
    }

    @Test
    fun `a whole chapter is asked for by verse ids, and search references are read`() {
        assertEquals("01001001-01001999", EsvBibleSource.chapterQuery(1, 1))
        assertEquals("65001001-65001999", EsvBibleSource.chapterQuery(65, 1))
        assertEquals(VerseRef(43, 3, 16), EsvBibleSource.parseEsvReference("John 3:16"))
        assertEquals(VerseRef(19, 23, 1), EsvBibleSource.parseEsvReference("Psalm 23:1"))
        assertEquals(VerseRef(22, 2, 4), EsvBibleSource.parseEsvReference("Song of Solomon 2:4"))
        assertEquals(VerseRef(65, 1, 3), EsvBibleSource.parseEsvReference("Jude 3"))
        assertEquals(VerseRef(62, 4, 8), EsvBibleSource.parseEsvReference("1 John 4:8"))
        assertNull(EsvBibleSource.parseEsvReference("Not a book 1:1"))
    }
}
