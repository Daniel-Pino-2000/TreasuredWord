package com.application.bibleapp.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The markup mirrors YouVersion's `format=html` passages (#366). The words are made up on
 * purpose: licensed Scripture text must never be copied into this public repository.
 */
class YouVersionHtmlParserTest {

    private fun verse(n: Int) = """<span class="yv-v" v="$n"></span><span class="yv-vlbl">$n</span>"""

    @Test
    fun `prose verses keep their text and drop printed verse numbers`() {
        val chapter = YouVersionHtmlParser.parse(
            """<div><div class="p">${verse(1)}Alpha beta gamma. ${verse(2)}Delta epsilon.</div></div>"""
        )
        assertEquals(listOf(1, 2), chapter.verses.map { it.number })
        assertEquals("Alpha beta gamma.", chapter.verses[0].plainText)
        assertEquals("Delta epsilon.", chapter.verses[1].plainText)
        assertTrue(chapter.verses[0].richContent.runs.first().paragraphBreakBefore)
        assertFalse(chapter.verses[1].richContent.runs.first().paragraphBreakBefore)
    }

    @Test
    fun `a heading belongs to the verse after it`() {
        val chapter = YouVersionHtmlParser.parse(
            """<div><div class="s yv-h"><span class="it">The Heading</span></div><div class="p">${verse(1)}Text one.</div>""" +
                """<div class="s yv-h">Second</div><div class="p">${verse(2)}Text two.</div></div>"""
        )
        assertEquals(listOf("The Heading"), chapter.verses[0].richContent.headings.map { it.text })
        assertEquals(listOf("Second"), chapter.verses[1].richContent.headings.map { it.text })
    }

    @Test
    fun `a major title that only repeats the chapter is dropped`() {
        val chapter = YouVersionHtmlParser.parse(
            """<div><div class="ms2 yv-h">Psalm 3</div><div class="ms yv-h">Book One</div><div class="q">${verse(1)}Line.</div></div>"""
        )
        assertEquals(listOf("Book One"), chapter.verses[0].richContent.headings.map { it.text })
    }

    @Test
    fun `a footnote marks the run before it and keeps its text, not its reference`() {
        val chapter = YouVersionHtmlParser.parse(
            """<div class="p">${verse(4)}Word one<span class="yv-n f"><span class="fr">3:4 </span><span class="ft">Or <span class="it">other</span></span></span> and more.</div>"""
        )
        val runs = chapter.verses.single().richContent.runs
        assertEquals("Word one", runs[0].text)
        assertEquals(0, runs[0].footnoteId)
        assertEquals("and more.", runs[1].text)
        assertEquals("Word one and more.", chapter.verses.single().plainText)
        val note = chapter.footnotes.single()
        assertEquals("Or other", note.text)
        assertEquals(4, note.verse)
    }

    @Test
    fun `words of Jesus are their own runs`() {
        val chapter = YouVersionHtmlParser.parse(
            """<div class="p">${verse(3)}He said: <span class="wj">Red words here.</span> Narration.</div>"""
        )
        val runs = chapter.verses.single().richContent.runs
        assertEquals(listOf(false, true, false), runs.map { it.isWordsOfJesus })
        assertEquals("Red words here.", runs[1].text)
    }

    @Test
    fun `small caps become capitals inside the same word`() {
        val chapter = YouVersionHtmlParser.parse("""<div class="q">${verse(1)}O L<span class="sc">ord</span>, hear.</div>""")
        assertEquals("O LORD, hear.", chapter.verses.single().plainText)
        assertEquals(1, chapter.verses.single().richContent.runs.size)
    }

    @Test
    fun `poetry lines start lines, and a footnote does not split one`() {
        val chapter = YouVersionHtmlParser.parse(
            """<div class="q">${verse(7)}First line</div><div class="q2">second <span class="yv-n f"><span class="fr">x</span><span class="ft">Note</span></span>line rest</div>"""
        )
        val runs = chapter.verses.single().richContent.runs
        assertEquals(1, runs[0].poemLevel)
        assertEquals(2, runs[1].poemLevel)
        assertEquals("second", runs[1].text)
        assertEquals(0, runs[1].footnoteId)
        assertNull(runs[2].poemLevel)
        assertTrue(runs[2].sameLine)
        assertEquals("line rest", runs[2].text)
    }

    @Test
    fun `a verse runs on across paragraphs with a line break`() {
        val chapter = YouVersionHtmlParser.parse(
            """<div class="p">${verse(5)}Start of verse.</div><div class="p">Rest of verse. ${verse(6)}Next.</div>"""
        )
        val five = chapter.verses.first()
        assertEquals("Start of verse. Rest of verse.", five.plainText)
        assertTrue(five.richContent.runs[1].lineBreakBefore)
    }

    @Test
    fun `a psalm title is a subtitle of the first verse, and its note goes with that verse`() {
        val chapter = YouVersionHtmlParser.parse(
            """<div class="d">A song<span class="yv-n f"><span class="fr">3:0 </span><span class="ft">Title note</span></span>.</div><div class="q">${verse(1)}Line.</div>"""
        )
        val heading = chapter.verses.single().richContent.headings.single()
        assertEquals("hebrew_subtitle", heading.type)
        assertEquals("A song.", heading.text)
        assertEquals(1, chapter.footnotes.single().verse)
    }

    @Test
    fun `a note before a poetry line's words opens that line`() {
        val chapter = YouVersionHtmlParser.parse(
            """<div class="q">${verse(8)}<span class="yv-n f"><span class="fr">3:8 </span><span class="ft">Note</span></span>Words of the line;</div>"""
        )
        val runs = chapter.verses.single().richContent.runs
        assertEquals(1, runs[0].poemLevel)
        assertEquals(0, runs[0].footnoteId)
        assertEquals("", runs[0].text)
        assertNull(runs[1].poemLevel)
        assertTrue(runs[1].sameLine)
    }

    @Test
    fun `a pilcrow in the text becomes a paragraph break`() {
        val chapter = YouVersionHtmlParser.parse(
            """<div class="q">${verse(2)}Line one.</div><div class="q">${verse(3)}¶But then.</div>"""
        )
        val three = chapter.verses[1]
        assertEquals("But then.", three.plainText)
        assertTrue(three.richContent.runs.first().paragraphBreakBefore)
        assertEquals(1, three.richContent.runs.first().poemLevel)
    }

    @Test
    fun `entities are decoded`() {
        val chapter = YouVersionHtmlParser.parse("""<div class="p">${verse(1)}A &amp; B &#8212; C&#x2019;s.</div>""")
        assertEquals("A & B — C’s.", chapter.verses.single().plainText)
    }
}

class YouVersionBookNameTest {
    @Test
    fun `the plural book name wins only when it extends the chapter name`() {
        assertEquals("Salmos", bookName("Salmo", "Salmos"))
        assertEquals("Psalms", bookName("Psalm", "Psalms"))
        assertEquals("Amós", bookName("Amós", "Amos"))
        assertEquals("Song of Solomon", bookName("Song of Solomon", "Song of Solomon"))
        assertEquals("Génesis", bookName(null, "Génesis"))
    }
}
