package com.application.bibleapp.viewmodel

import com.application.bibleapp.data.model.PassageVerse
import com.application.bibleapp.data.model.VerseUI
import com.application.bibleapp.utils.VerseReference
import com.application.bibleapp.data.remote.SemanticSearchResult
import com.application.bibleapp.data.remote.ServerWake
import com.application.bibleapp.data.remote.VerseRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Search screen's "Related by meaning" section (#287), with a fake server and Bible. */
@OptIn(ExperimentalCoroutinesApi::class)
class MeaningSearchTest {

    private val queries = mutableListOf<String>()
    private var answer: SemanticSearchResult = SemanticSearchResult.Found(listOf(VerseRef(40, 6, 34), VerseRef(20, 27, 1)))
    private var version = "kjv"
    private var online = true

    /** Reads each reference as "<version> b:c:v", the way the phone's Bibles would. */
    private suspend fun resolve(refs: List<VerseRef>): List<MeaningVerse> =
        refs.map { MeaningVerse(it.bookId, it.chapter, it.verse, "$version ${it.bookId}:${it.chapter}:${it.verse}", version) }

    /** Reads each key passage as one verse, "<version> <reference>". */
    private suspend fun resolvePassages(refs: List<VerseReference>): List<KeyPassage> =
        refs.map { KeyPassage(it, listOf(PassageVerse(it.startVerse ?: 1, "$version $it")), version) }

    private fun TestScope.meaningSearch(serverWake: ServerWake? = null) = MeaningSearch(
        scope = this,
        search = { query -> queries += query; answer },
        resolve = ::resolve,
        isEligible = { isMeaningSearchEligible(it) { query -> query == "John 3:16" } },
        phoneIsOnline = { online },
        serverWake = serverWake,
        resolvePassages = ::resolvePassages
    )

    @Test
    fun `a submitted search shows the verses in the reader's translation`() = runTest {
        val search = meaningSearch()

        search.submit("  worry about tomorrow ")
        advanceUntilIdle()

        assertEquals(listOf("worry about tomorrow"), queries)
        assertEquals(
            MeaningSearchState(
                "worry about tomorrow",
                MeaningSearchStatus.DONE,
                listOf(MeaningVerse(40, 6, 34, "kjv 40:6:34", "kjv"), MeaningVerse(20, 27, 1, "kjv 20:27:1", "kjv"))
            ),
            search.state.value
        )
    }

    @Test
    fun `typing alone never asks the server`() = runTest {
        val search = meaningSearch()

        search.onQueryChange("worry")
        search.onQueryChange("worry about")
        advanceUntilIdle()

        assertTrue(queries.isEmpty())
        assertEquals(MeaningSearchState(), search.state.value)
    }

    @Test
    fun `verse references, quoted phrases and single letters are not searched by meaning`() = runTest {
        val search = meaningSearch()

        listOf("John 3:16", "\"love one another\"", "“in the beginning”", "«paz»", "a", "  ").forEach {
            search.submit(it)
            advanceUntilIdle()
        }

        assertTrue(queries.isEmpty())
        assertEquals(MeaningSearchState(), search.state.value)
    }

    @Test
    fun `one Chinese character is a word worth searching`() = runTest {
        assertTrue(isMeaningSearchEligible("爱") { false })
    }

    @Test
    fun `submitting the same search again doesn't ask the server again`() = runTest {
        val search = meaningSearch()

        search.submit("peace")
        advanceUntilIdle()
        search.submit("peace ")
        advanceUntilIdle()

        assertEquals(listOf("peace"), queries)
    }

    @Test
    fun `after a failure the same search can be tried again`() = runTest {
        val search = meaningSearch()
        answer = SemanticSearchResult.Unavailable

        search.submit("peace")
        advanceUntilIdle()
        answer = SemanticSearchResult.Found(listOf(VerseRef(43, 14, 27)))
        search.submit("peace")
        advanceUntilIdle()

        assertEquals(listOf("peace", "peace"), queries)
        assertEquals(MeaningSearchStatus.DONE, search.state.value.status)
    }

    @Test
    fun `the server's refusals and lost connections each get their line`() = runTest {
        val search = meaningSearch()

        answer = SemanticSearchResult.Unavailable
        search.submit("peace")
        advanceUntilIdle()
        assertEquals(MeaningSearchState("peace", MeaningSearchStatus.UNAVAILABLE), search.state.value)

        answer = SemanticSearchResult.Offline
        search.submit("hope")
        advanceUntilIdle()
        assertEquals(MeaningSearchState("hope", MeaningSearchStatus.OFFLINE), search.state.value)
    }

    @Test
    fun `an offline phone doesn't try the server at all`() = runTest {
        val search = meaningSearch()
        online = false

        search.submit("peace")
        advanceUntilIdle()

        assertTrue(queries.isEmpty())
        assertEquals(MeaningSearchState("peace", MeaningSearchStatus.OFFLINE), search.state.value)
    }

    @Test
    fun `a sleeping server is woken first, and the section says it's waiting`() = runTest {
        val woken = CompletableDeferred<Unit>()
        val wake = object : ServerWake {
            override fun isAwake() = false
            override suspend fun wake() = woken.await()
        }
        val search = meaningSearch(wake)

        search.submit("peace")
        runCurrent()
        assertEquals(MeaningSearchStatus.WAKING, search.state.value.status)
        assertTrue(queries.isEmpty())

        woken.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("peace"), queries)
        assertEquals(MeaningSearchStatus.DONE, search.state.value.status)
    }

    @Test
    fun `typing something else clears the section and drops a search still running`() = runTest {
        val reply = CompletableDeferred<SemanticSearchResult>()
        val search = MeaningSearch(this, search = { reply.await() }, resolve = ::resolve, isEligible = { true })

        search.submit("peace")
        runCurrent()
        assertTrue(search.state.value.isLoading)
        search.onQueryChange("peace ") // the same search
        assertTrue(search.state.value.isLoading)
        search.onQueryChange("peace of")
        reply.complete(answer)
        advanceUntilIdle()

        assertEquals(MeaningSearchState(), search.state.value)
    }

    @Test
    fun `a new translation shows the same verses in it, without asking the server again`() = runTest {
        val search = meaningSearch()
        search.submit("peace")
        advanceUntilIdle()

        version = "spa"
        search.onVersionChange()
        advanceUntilIdle()

        assertEquals(listOf("peace"), queries)
        assertEquals(listOf("spa", "spa"), search.state.value.results.map { it.versionId })
    }

    @Test
    fun `a matching topic guide's key passages come first, and their verses aren't repeated below`() = runTest {
        val rest = VerseReference(40, 11, 28, 30)
        val shepherd = VerseReference(19, 23)
        answer = SemanticSearchResult.Found(
            listOf(VerseRef(40, 11, 29), VerseRef(19, 42, 5), VerseRef(19, 23, 4), VerseRef(40, 11, 27)),
            listOf(rest, shepherd)
        )
        val search = meaningSearch()

        search.submit("burned out")
        advanceUntilIdle()

        val state = search.state.value
        assertEquals(MeaningSearchStatus.DONE, state.status)
        assertEquals(listOf(rest, shepherd), state.keyPassages.map { it.reference })
        // Matthew 11:29 is in 11:28-30 and Psalm 23:4 in the whole of Psalm 23; 11:27 is outside.
        assertEquals(listOf(VerseRef(19, 42, 5), VerseRef(40, 11, 27)), state.results.map { VerseRef(it.bookId, it.chapter, it.verse) })

        version = "spa"
        search.onVersionChange()
        advanceUntilIdle()

        assertEquals(listOf("burned out"), queries)
        assertEquals(listOf("spa", "spa"), search.state.value.keyPassages.map { it.versionId })
        assertEquals(listOf("spa", "spa"), search.state.value.results.map { it.versionId })
    }

    @Test
    fun `without a matching guide there are no key passages`() = runTest {
        val search = meaningSearch()
        search.submit("peace")
        advanceUntilIdle()

        assertEquals(MeaningSearchStatus.DONE, search.state.value.status)
        assertTrue(search.state.value.keyPassages.isEmpty())
    }

    @Test
    fun `a verse the word results already show isn't repeated`() {
        val meaning = listOf(MeaningVerse(40, 6, 34, "Take therefore no thought…", "kjv"), MeaningVerse(20, 27, 1, "Boast not…", "kjv"))
        val words = listOf(VerseUI(id = 1, text = "Take therefore no thought for the morrow", bookId = 40, chapter = 6, verse = 34))

        assertEquals(listOf(meaning[1]), meaning.withoutWordResults(words))
        assertFalse(meaning.withoutWordResults(emptyList()).isEmpty())
    }

    @Test
    fun `each verse comes from the selected translation, or the KJV when it lacks it`() = runTest {
        val chaptersRead = mutableListOf<Triple<String, Int, Int>>()
        fun chapter(bookId: Int, chapter: Int, vararg verses: Pair<Int, String>) =
            verses.map { (n, text) -> VerseUI(id = n, text = text, bookId = bookId, chapter = chapter, verse = n) }

        val verses = resolveMeaningVerses(
            listOf(VerseRef(43, 14, 27), VerseRef(43, 14, 1), VerseRef(19, 23, 1)),
            selectedVersionId = "nt_only"
        ) { versionId, bookId, chapter ->
            chaptersRead += Triple(versionId, bookId, chapter)
            when {
                versionId == "nt_only" && bookId == 43 -> chapter(43, 14, 1 to "Que no se turbe…", 27 to "La paz os dejo…")
                versionId == "kjv" && bookId == 19 -> chapter(19, 23, 1 to "The LORD is my shepherd…")
                else -> emptyList()
            }
        }

        assertEquals(
            listOf(
                MeaningVerse(43, 14, 27, "La paz os dejo…", "nt_only"),
                MeaningVerse(43, 14, 1, "Que no se turbe…", "nt_only"),
                MeaningVerse(19, 23, 1, "The LORD is my shepherd…", "kjv")
            ),
            verses
        )
        // John 14 was read once for both of its verses.
        assertEquals(1, chaptersRead.count { it == Triple("nt_only", 43, 14) })
    }

    @Test
    fun `each key passage comes whole from one translation, the KJV when the selected one lacks part of it`() = runTest {
        fun chapter(bookId: Int, chapter: Int, vararg verses: Pair<Int, String>) =
            verses.map { (n, text) -> VerseUI(id = n, text = text, bookId = bookId, chapter = chapter, verse = n) }

        val passages = resolveKeyPassages(
            listOf(VerseReference(40, 11, 28, 30), VerseReference(19, 13), VerseReference(19, 147, 3), VerseReference(40, 99, 1)),
            selectedVersionId = "partial"
        ) { versionId, bookId, chapter ->
            when {
                versionId == "partial" && bookId == 40 -> chapter(40, 11, 28 to "Venid a mí…", 29 to "Llevad mi yugo…", 30 to "Porque mi yugo…")
                versionId == "partial" && bookId == 19 && chapter == 147 -> chapter(19, 147, 2 to "…")
                versionId == "kjv" && bookId == 19 && chapter == 147 -> chapter(19, 147, 3 to "He healeth the broken in heart…")
                versionId == "partial" && bookId == 19 && chapter == 13 -> chapter(19, 13, 1 to "¿Hasta cuándo…", 2 to "¿Hasta cuándo…")
                else -> emptyList()
            }
        }

        assertEquals(
            listOf(
                KeyPassage(VerseReference(40, 11, 28, 30), listOf(PassageVerse(28, "Venid a mí…"), PassageVerse(29, "Llevad mi yugo…"), PassageVerse(30, "Porque mi yugo…")), "partial"),
                KeyPassage(VerseReference(19, 13), listOf(PassageVerse(1, "¿Hasta cuándo…"), PassageVerse(2, "¿Hasta cuándo…")), "partial"),
                KeyPassage(VerseReference(19, 147, 3), listOf(PassageVerse(3, "He healeth the broken in heart…")), "kjv")
            ),
            passages
        )
    }
}
