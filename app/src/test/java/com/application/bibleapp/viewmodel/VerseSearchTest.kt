package com.application.bibleapp.viewmodel

import com.application.bibleapp.data.model.VerseUI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VerseSearchTest {

    /** Records every search it runs and answers with one verse naming the query and version. */
    private class FakeSearch(private val latencyMillis: Long = 0) {
        val calls = mutableListOf<Pair<String, String>>()
        var failWith: Exception? = null

        suspend fun search(query: String, versionId: String): List<VerseUI> {
            calls += query to versionId
            delay(latencyMillis)
            failWith?.let { throw it }
            return listOf(VerseUI(id = null, text = "$query@$versionId", bookId = 1, chapter = 1, verse = 1))
        }
    }

    private fun TestScope.verseSearch(fake: FakeSearch, versionId: () -> String = { "kjv" }) =
        VerseSearch(this, fake::search, versionId)

    @Test
    fun `typing does not show no results before a search has run`() = runTest {
        val fake = FakeSearch()
        val search = verseSearch(fake)

        search.onQueryChange("love")
        advanceTimeBy(VerseSearch.DEBOUNCE_MILLIS - 1)
        runCurrent()

        assertEquals("love", search.query.value)
        assertEquals(SearchUiState(), search.state.value)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `search runs once typing pauses, only for the final text`() = runTest {
        val fake = FakeSearch()
        val search = verseSearch(fake)

        search.onQueryChange("lo")
        advanceTimeBy(100)
        search.onQueryChange("lov")
        advanceTimeBy(100)
        search.onQueryChange("love")
        advanceUntilIdle()

        assertEquals(listOf("love" to "kjv"), fake.calls)
        assertEquals("love", search.state.value.query)
        assertEquals(listOf("love@kjv"), search.state.value.results.map { it.text })
        assertFalse(search.state.value.isSearching)
    }

    @Test
    fun `shows searching while the query runs, keeping previous results`() = runTest {
        val fake = FakeSearch(latencyMillis = 1_000)
        val search = verseSearch(fake)
        search.onQueryChange("love")
        advanceUntilIdle()

        search.onQueryChange("faith")
        advanceTimeBy(VerseSearch.DEBOUNCE_MILLIS + 1)

        assertTrue(search.state.value.isSearching)
        assertEquals("love", search.state.value.query)
        assertEquals(listOf("love@kjv"), search.state.value.results.map { it.text })
    }

    @Test
    fun `a slow earlier search never overwrites a newer one`() = runTest {
        val fake = FakeSearch(latencyMillis = 1_000)
        val search = verseSearch(fake)

        search.onQueryChange("love")
        search.submit()
        advanceTimeBy(500) // "love" is mid-search
        search.onQueryChange("faith")
        search.submit()
        advanceUntilIdle()

        assertEquals("faith", search.state.value.query)
        assertEquals(listOf("faith@kjv"), search.state.value.results.map { it.text })
    }

    @Test
    fun `submit searches right away`() = runTest {
        val fake = FakeSearch()
        val search = verseSearch(fake)

        search.onQueryChange("love")
        search.submit()
        runCurrent()

        assertEquals(listOf("love" to "kjv"), fake.calls)
        assertEquals("love", search.state.value.query)
    }

    @Test
    fun `one character only searches when submitted`() = runTest {
        val fake = FakeSearch()
        val search = verseSearch(fake)

        search.onQueryChange("a")
        advanceUntilIdle()
        assertTrue(fake.calls.isEmpty())

        search.submit()
        advanceUntilIdle()
        assertEquals(listOf("a" to "kjv"), fake.calls)
    }

    @Test
    fun `clearing the text goes back to idle and cancels a pending search`() = runTest {
        val fake = FakeSearch()
        val search = verseSearch(fake)
        search.onQueryChange("love")
        advanceUntilIdle()

        search.onQueryChange("faith")
        search.onQueryChange("")
        advanceUntilIdle()

        assertEquals(SearchUiState(), search.state.value)
        assertEquals(listOf("love" to "kjv"), fake.calls)
    }

    @Test
    fun `trailing whitespace does not search again`() = runTest {
        val fake = FakeSearch()
        val search = verseSearch(fake)
        search.onQueryChange("love")
        advanceUntilIdle()

        search.onQueryChange("love ")
        advanceUntilIdle()

        assertEquals(1, fake.calls.size)
    }

    @Test
    fun `a failed search reports an error instead of no results`() = runTest {
        val fake = FakeSearch().apply { failWith = IllegalStateException("DB not initialized") }
        val search = verseSearch(fake)

        search.onQueryChange("love")
        advanceUntilIdle()

        assertTrue(search.state.value.hasError)
        assertEquals("love", search.state.value.query)

        fake.failWith = null
        search.submit()
        advanceUntilIdle()
        assertFalse(search.state.value.hasError)
        assertEquals(listOf("love@kjv"), search.state.value.results.map { it.text })
    }

    @Test
    fun `switching version searches the shown query again in the new version`() = runTest {
        val fake = FakeSearch()
        var version = "kjv"
        val search = verseSearch(fake) { version }
        search.onQueryChange("love")
        advanceUntilIdle()

        version = "bsb"
        search.onVersionChange()
        advanceUntilIdle()

        assertEquals(listOf("love@bsb"), search.state.value.results.map { it.text })
    }

    @Test
    fun `switching version with nothing searched does nothing`() = runTest {
        val fake = FakeSearch()
        val search = verseSearch(fake)

        search.onVersionChange()
        advanceUntilIdle()

        assertTrue(fake.calls.isEmpty())
    }
}
