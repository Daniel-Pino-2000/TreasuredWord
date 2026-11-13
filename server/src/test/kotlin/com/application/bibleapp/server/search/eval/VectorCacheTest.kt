package com.application.bibleapp.server.search.eval

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The verses' vectors on disk (#287), so the Bible is embedded once, not on every run. */
class VectorCacheTest {

    private val folder = Files.createTempDirectory("vector-cache").toFile()
    private val file = File(folder, "cache/bge-m3-kjv.vec")

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    private fun cache(model: String = "@cf/baai/bge-m3", translation: String = "kjv") = VectorCache(file, model, translation)

    private val genesis = VerseRef(1, 1, 1) to floatArrayOf(0.25f, -1f, 3f)
    private val psalm = VerseRef(19, 119, 176) to floatArrayOf(1f, 2f, 3f)
    private val revelation = VerseRef(66, 22, 21) to floatArrayOf(-0.5f, 0f, 0.5f)

    @Test
    fun aMissingFileIsAnEmptyCache() {
        assertTrue(cache().load().isEmpty())
    }

    @Test
    fun whatIsAppendedIsLoadedBackAcrossRuns() {
        cache().apply { load(); append(listOf(genesis, psalm)) }
        // The next day's run: a new cache object on the same file.
        cache().apply { load(); append(listOf(revelation)) }

        val loaded = cache().load()
        assertEquals(listOf(genesis.first, psalm.first, revelation.first), loaded.keys.toList())
        assertContentEquals(psalm.second, loaded.getValue(psalm.first))
        assertContentEquals(revelation.second, loaded.getValue(revelation.first))
    }

    @Test
    fun aRecordCutOffMidWriteIsDroppedAndOverwritten() {
        cache().append(listOf(genesis, psalm))
        // Ctrl+C in the middle of the next record.
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 5) }

        val afterCrash = cache()
        assertEquals(listOf(genesis.first), afterCrash.load().keys.toList())
        afterCrash.append(listOf(revelation))

        assertEquals(listOf(genesis.first, revelation.first), cache().load().keys.toList())
    }

    @Test
    fun anotherModelsVectorsAreNeverMixedIn() {
        cache().append(listOf(genesis))

        assertFailsWith<IllegalStateException> { cache(model = "@cf/qwen/qwen3-embedding-0.6b").load() }
        assertFailsWith<IllegalStateException> { cache(translation = "bsb").load() }
    }

    @Test
    fun vectorsOfAnotherSizeAreRefused() {
        cache().append(listOf(genesis))

        assertFailsWith<IllegalArgumentException> { cache().apply { load() }.append(listOf(VerseRef(1, 1, 2) to floatArrayOf(1f))) }
    }

    @Test
    fun fileNamesComeFromTheModelAndTranslation() {
        assertEquals("bge-m3-kjv.vec", VectorCache.fileName("@cf/baai/bge-m3", "kjv"))
        assertEquals("qwen3-embedding-0.6b-bsb.vec", VectorCache.fileName("@cf/qwen/qwen3-embedding-0.6b", "BSB"))
    }
}
