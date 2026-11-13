package com.application.bibleapp.server.search.eval

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * The verses' vectors, kept on disk so embedding the Bible (~1,200 of the 10,000 free neurons a
 * day) happens once per model and translation, not on every run (#287).
 *
 * One file per model and translation: a header naming both and the vector size, then one record
 * per verse (book, chapter, verse, then the floats). Records are appended after every batch, so a
 * run stopped by the daily limit, a dropped connection or Ctrl+C keeps what it paid for; a record
 * cut off halfway is dropped on the next [load] and overwritten.
 */
class VectorCache(val file: File, private val model: String, private val translation: String) {

    private var dimensions: Int? = null
    /** Where the last whole record ends; anything after it is a record cut off mid-write. */
    private var validLength = 0L
    private var loaded = false

    fun load(): MutableMap<VerseRef, FloatArray> {
        loaded = true
        val vectors = LinkedHashMap<VerseRef, FloatArray>()
        if (!file.isFile || file.length() == 0L) {
            dimensions = null
            validLength = 0
            return vectors
        }
        DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
            val header = try {
                readHeader(input)
            } catch (e: EOFException) {
                // Stopped before the header was even complete: start over.
                dimensions = null
                validLength = 0
                return vectors
            }
            check(header.model == model && header.translation == translation) {
                "${file.name} holds ${header.model} vectors of ${header.translation}, not $model of $translation. Delete it to start over."
            }
            dimensions = header.dimensions
            val recordSize = recordSize(header.dimensions)
            val records = (file.length() - header.size) / recordSize
            repeat(records.toInt()) {
                val ref = VerseRef(input.readUnsignedByte(), input.readUnsignedShort(), input.readUnsignedShort())
                vectors[ref] = FloatArray(header.dimensions) { input.readFloat() }
            }
            validLength = header.size + records * recordSize
        }
        return vectors
    }

    /** Adds vectors to the end of the file (and writes the header first, for a new file). */
    fun append(entries: List<Pair<VerseRef, FloatArray>>) {
        if (entries.isEmpty()) return
        if (!loaded) load()
        val size = entries.first().second.size
        require(entries.all { it.second.size == size }) { "Vectors of different sizes" }
        dimensions?.let { require(it == size) { "${file.name} holds vectors of $it numbers, not $size" } }

        file.parentFile?.mkdirs()
        if (file.isFile && file.length() > validLength) {
            // Drop a record that was cut off, so the new ones line up.
            RandomAccessFile(file, "rw").use { it.setLength(validLength) }
        }
        DataOutputStream(BufferedOutputStream(FileOutputStream(file, true), 1 shl 16)).use { output ->
            if (dimensions == null) {
                val header = header(size)
                output.write(header)
                validLength = header.size.toLong()
                dimensions = size
            }
            for ((ref, vector) in entries) {
                output.writeByte(ref.book)
                output.writeShort(ref.chapter)
                output.writeShort(ref.verse)
                vector.forEach { output.writeFloat(it) }
            }
        }
        validLength += entries.size * recordSize(size)
    }

    private class Header(val model: String, val translation: String, val dimensions: Int, val size: Long)

    private fun readHeader(input: DataInputStream): Header {
        check(input.readInt() == MAGIC) { "${file.name} isn't a vector cache. Delete it to start over." }
        check(input.readInt() == VERSION) { "${file.name} is from another version of the evaluation. Delete it to start over." }
        val model = input.readUTF()
        val translation = input.readUTF()
        val dimensions = input.readInt()
        check(dimensions in 1..65_536) { "${file.name} is damaged. Delete it to start over." }
        return Header(model, translation, dimensions, encodeHeader(model, translation, dimensions).size.toLong())
    }

    private fun header(dimensions: Int) = encodeHeader(model, translation, dimensions)

    private fun encodeHeader(model: String, translation: String, dimensions: Int): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                it.writeInt(MAGIC)
                it.writeInt(VERSION)
                it.writeUTF(model)
                it.writeUTF(translation)
                it.writeInt(dimensions)
            }
        }.toByteArray()

    private fun recordSize(dimensions: Int) = 1L + 2 + 2 + 4L * dimensions

    companion object {
        private const val MAGIC = 0x54575643 // "TWVC": TreasuredWord vector cache
        private const val VERSION = 1

        /** `bge-m3-kjv.vec` for `@cf/baai/bge-m3` and the KJV. */
        fun fileName(model: String, translation: String) =
            "${slug(model.substringAfterLast('/'))}-${slug(translation)}.vec"

        fun slug(text: String) = text.lowercase().replace(Regex("[^a-z0-9.]+"), "-").trim('-')
    }
}
