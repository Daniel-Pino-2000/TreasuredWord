package com.application.bibleapp.worker

import com.application.bibleapp.data.model.Highlight
import com.application.bibleapp.data.model.Note
import com.application.bibleapp.data.model.SyncStatus
import com.application.bibleapp.data.remote.BackendHttpException
import com.application.bibleapp.data.remote.HighlightListResponseDto
import com.application.bibleapp.data.remote.HighlightResponseDto
import com.application.bibleapp.data.remote.NoteListResponseDto
import com.application.bibleapp.data.remote.NoteResponseDto
import com.application.bibleapp.data.remote.ReadingProgressResponseDto
import com.application.bibleapp.data.remote.VerseLocationDto
import com.application.bibleapp.data.repository.ReadingPosition
import com.application.bibleapp.utils.formatIsoTimestamp
import com.application.bibleapp.utils.parseIsoTimestamp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class SyncEngineTest {

    private val john316 = listOf(VerseLocationDto(43, 3, 16))
    private val serverNow = "2026-10-23T10:00:00.123456Z"

    // ---- #80: a pass that couldn't reach the server fails, instead of reporting "Synced" ----

    @Test
    fun `a pull that can't reach the server fails the pass and keeps the watermark`() = runTest {
        val local = FakeLocalStore().apply { highlightsWatermark = "2026-10-01T00:00:00.000Z" }
        val highlights = FakeHighlightApi().apply { listResult = Result.failure(IOException("timeout")) }

        assertFailsPass { engine(local, highlights).runPass() }
        assertEquals("2026-10-01T00:00:00.000Z", local.highlightsWatermark)
    }

    @Test
    fun `a push that can't reach the server fails the pass`() = runTest {
        val local = FakeLocalStore().apply { addHighlight(color = 1) }
        val highlights = FakeHighlightApi().apply { createResult = { Result.failure(IOException("no connection")) } }

        assertFailsPass { engine(local, highlights).runPass() }
        assertEquals(SyncStatus.PENDING, local.highlights.single().syncStatus)
    }

    @Test
    fun `a server error fails the pass, so it's retried`() = runTest {
        val notes = FakeNoteApi().apply { listResult = Result.failure(BackendHttpException(503, "Note")) }

        assertFailsPass { engine(FakeLocalStore(), notes = notes).runPass() }
    }

    @Test
    fun `failing to reach the server for reading progress fails the pass`() = runTest {
        val progress = FakeReadingProgressApi().apply { getResult = Result.failure(IOException("timeout")) }

        assertFailsPass { engine(FakeLocalStore(), progress = progress).runPass() }
    }

    @Test
    fun `one item the server refuses doesn't block the rest of the queue`() = runTest {
        val local = FakeLocalStore().apply {
            addHighlight(color = 1)
            addHighlight(color = 2)
        }
        val highlights = FakeHighlightApi().apply {
            createResult = { color -> if (color == 1) Result.failure(BackendHttpException(400, "Highlight")) else Result.success(serverHighlight("r2", color)) }
        }

        engine(local, highlights).runPass()

        val (refused, pushed) = local.highlights.sortedBy { it.color }
        assertEquals(SyncStatus.PENDING, refused.syncStatus)
        assertNull(refused.remoteId)
        assertEquals(SyncStatus.SYNCED, pushed.syncStatus)
        assertEquals("r2", pushed.remoteId)
    }

    // ---- #83: changes made while a row is uploading aren't lost ----

    @Test
    fun `a recolor made while the highlight was uploading is sent next pass`() = runTest {
        val local = FakeLocalStore()
        val row = local.addHighlight(color = 1)
        val highlights = FakeHighlightApi().apply {
            // The person recolors the highlight while its create request is out.
            createResult = { color ->
                local.recolor(row.localId, 2)
                Result.success(serverHighlight("r1", color))
            }
        }
        val engine = engine(local, highlights)

        engine.runPass()
        val afterFirst = local.highlights.single()
        assertEquals(SyncStatus.PENDING, afterFirst.syncStatus)
        assertEquals("r1", afterFirst.remoteId)

        engine.runPass()
        assertEquals(listOf("r1" to 2), highlights.recolors)
        assertEquals(1, highlights.creates.size)
        assertEquals(SyncStatus.SYNCED, local.highlights.single().syncStatus)
    }

    @Test
    fun `a delete made while the note was uploading is sent next pass`() = runTest {
        val local = FakeLocalStore()
        val row = local.addNote(text = "First draft")
        val notes = FakeNoteApi().apply {
            createResult = { text ->
                local.softDeleteNote(row.localId)
                Result.success(serverNote("n1", text))
            }
        }
        val engine = engine(local, notes = notes)

        engine.runPass()
        engine.runPass()

        assertEquals(listOf("n1"), notes.deletes)
        assertTrue(local.notes.isEmpty())
    }

    @Test
    fun `a highlight restored while its delete was out is created again`() = runTest {
        val local = FakeLocalStore()
        val row = local.addHighlight(color = 1, remoteId = "r1", deleted = true)
        val highlights = FakeHighlightApi().apply {
            deleteResult = {
                local.restore(row.localId)
                Result.success(Unit)
            }
            createResult = { color -> Result.success(serverHighlight("r2", color)) }
        }
        val engine = engine(local, highlights)

        engine.runPass()
        engine.runPass()

        val restored = local.highlights.single()
        assertEquals("r2", restored.remoteId)
        assertEquals(SyncStatus.SYNCED, restored.syncStatus)
    }

    @Test
    fun `a 404 on recolor re-creates the highlight instead of retrying forever`() = runTest {
        val local = FakeLocalStore().apply { addHighlight(color = 3, remoteId = "gone") }
        val highlights = FakeHighlightApi().apply {
            recolorResult = { Result.failure(BackendHttpException(404, "Highlight")) }
            createResult = { color -> Result.success(serverHighlight("fresh", color)) }
        }
        val engine = engine(local, highlights)

        engine.runPass()
        engine.runPass()

        assertEquals("fresh", local.highlights.single().remoteId)
    }

    // ---- #84: the watermark follows the server's clock ----

    @Test
    fun `the next pull starts from the server's clock less the margin, not the phone's`() = runTest {
        val local = FakeLocalStore()
        val phoneTenMinutesFast = parseIsoTimestamp(serverNow)!! + 10 * 60_000
        val highlights = FakeHighlightApi().apply { listResult = Result.success(HighlightListResponseDto(emptyList(), serverNow)) }
        val notes = FakeNoteApi().apply { listResult = Result.success(NoteListResponseDto(emptyList(), serverNow)) }

        engine(local, highlights, notes, clock = { phoneTenMinutesFast }).runPass()

        val expected = formatIsoTimestamp(parseIsoTimestamp(serverNow)!! - WATERMARK_SAFETY_MARGIN_MS)
        assertEquals(expected, local.highlightsWatermark)
        assertEquals(expected, local.notesWatermark)
    }

    @Test
    fun `an older server without serverTime falls back to the phone's clock`() {
        val phone = parseIsoTimestamp("2026-10-23T10:00:00.000Z")!!
        assertEquals("2026-10-23T09:59:00.000Z", nextSyncWatermark(serverTime = null, requestedAtMillis = phone))
    }

    // ---- #79: screens only reload when a pass changed something ----

    @Test
    fun `a pass reports a change only when the pull changed local content`() = runTest {
        val local = FakeLocalStore()
        val highlights = FakeHighlightApi().apply {
            listResult = Result.success(HighlightListResponseDto(listOf(serverHighlight("r9", 5)), serverNow))
        }
        val engine = engine(local, highlights)

        assertTrue(engine.runPass().contentChanged)
        // The same highlight again (the safety margin re-pulls it): nothing to reload.
        assertFalse(engine.runPass().contentChanged)
    }

    @Test
    fun `a pull doesn't overwrite a local change that hasn't been pushed yet`() = runTest {
        val local = FakeLocalStore().apply { addHighlight(color = 7, remoteId = "r1") }
        val highlights = FakeHighlightApi().apply {
            recolorResult = { Result.failure(BackendHttpException(400, "Highlight")) }
            listResult = Result.success(HighlightListResponseDto(listOf(serverHighlight("r1", 1)), serverNow))
        }

        engine(local, highlights).runPass()

        assertEquals(7, local.highlights.single().color)
        assertEquals(SyncStatus.PENDING, local.highlights.single().syncStatus)
    }

    // ---- #82: reading progress ----

    @Test
    fun `an untouched default position is never pushed`() = runTest {
        val progress = FakeReadingProgressApi()

        engine(FakeLocalStore(), progress = progress).runPass()

        assertTrue(progress.updates.isEmpty())
    }

    @Test
    fun `a move is pushed stamped with when it happened, not with now`() = runTest {
        val local = FakeLocalStore().apply {
            reading = reading.copy(position = ReadingPosition(43, 5, 1), changedAt = "2026-10-20T08:00:00.000Z", hasLocalPosition = true)
        }
        val progress = FakeReadingProgressApi()

        engine(local, progress = progress, clock = { parseIsoTimestamp(serverNow)!! }).runPass()

        assertEquals(listOf("2026-10-20T08:00:00.000Z"), progress.updates.map { it.readAt })
        assertEquals("2026-10-20T08:00:00.000Z", local.reading.pushedAt)
    }

    @Test
    fun `a position already pushed isn't sent again`() = runTest {
        val local = FakeLocalStore().apply {
            reading = reading.copy(changedAt = "2026-10-20T08:00:00.000Z", pushedAt = "2026-10-20T08:00:00.000Z", hasLocalPosition = true)
        }
        val progress = FakeReadingProgressApi()

        engine(local, progress = progress).runPass()

        assertTrue(progress.updates.isEmpty())
    }

    @Test
    fun `an older local position doesn't overwrite a newer one from another device`() = runTest {
        // This device last read Genesis 1 a week ago; another device read John 5 this morning.
        val local = FakeLocalStore().apply {
            reading = reading.copy(changedAt = "2026-10-16T08:00:00.000Z", pushedAt = "2026-10-16T08:00:00.000Z", hasLocalPosition = true)
        }
        val progress = FakeReadingProgressApi().apply {
            getResult = Result.success(ReadingProgressResponseDto("kjv", 43, 5, 1, "2026-10-23T09:00:00Z"))
        }

        val outcome = engine(local, progress = progress).runPass()

        assertTrue(progress.updates.isEmpty())
        assertEquals(ReadingPosition(43, 5, 1), local.reading.position)
        // This device had a position of its own, so the reader isn't moved out from under anyone.
        assertNull(outcome.restoredReadingPosition)
    }

    @Test
    fun `a newer local position isn't replaced by an older one from the server`() = runTest {
        val local = FakeLocalStore().apply {
            reading = reading.copy(position = ReadingPosition(19, 23, 1), changedAt = "2026-10-23T09:30:00.000Z", hasLocalPosition = true)
        }
        val progress = FakeReadingProgressApi().apply {
            getResult = Result.success(ReadingProgressResponseDto("kjv", 1, 1, 1, "2026-10-23T09:00:00Z"))
        }

        engine(local, progress = progress).runPass()

        assertEquals(ReadingPosition(19, 23, 1), local.reading.position)
    }

    @Test
    fun `a fresh install restores the server's position`() = runTest {
        val progress = FakeReadingProgressApi().apply {
            getResult = Result.success(ReadingProgressResponseDto("kjv", 43, 5, 2, "2026-10-23T09:00:00Z"))
        }
        val local = FakeLocalStore()

        val outcome = engine(local, progress = progress).runPass()

        assertEquals(ReadingPosition(43, 5, 2), outcome.restoredReadingPosition)
        assertTrue(progress.updates.isEmpty())
    }

    @Test
    fun `server position wins only when it's newer`() {
        assertTrue(serverPositionWins("2026-10-23T09:00:00Z", localChangedAt = null))
        assertTrue(serverPositionWins("2026-10-23T09:00:00.000001Z", "2026-10-23T08:59:59.999Z"))
        assertFalse(serverPositionWins("2026-10-23T09:00:00Z", "2026-10-23T09:00:00.000Z"))
        assertFalse(serverPositionWins("2026-10-23T09:00:00Z", "2026-10-23T09:30:00.000Z"))
        assertFalse(serverPositionWins("not a time", null))
    }

    // ---- helpers ----

    private fun engine(
        local: FakeLocalStore,
        highlights: FakeHighlightApi = FakeHighlightApi(),
        notes: FakeNoteApi = FakeNoteApi(),
        progress: FakeReadingProgressApi = FakeReadingProgressApi(),
        clock: () -> Long = { parseIsoTimestamp(serverNow)!! }
    ) = SyncEngine(local, highlights, notes, progress, clock, log = {})

    private suspend fun assertFailsPass(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected the pass to fail")
        } catch (expected: SyncFailedException) {
        }
    }

    private fun serverHighlight(id: String, color: Int) =
        HighlightResponseDto(id, "kjv", john316, color, createdAt = serverNow, updatedAt = serverNow)

    private fun serverNote(id: String, text: String) =
        NoteResponseDto(id, "kjv", john316, text, createdAt = serverNow, updatedAt = serverNow)
}

private class FakeHighlightApi : HighlightSyncApi {
    var listResult: Result<HighlightListResponseDto> = Result.success(HighlightListResponseDto(emptyList(), "2026-10-23T10:00:00Z"))
    var createResult: (Int) -> Result<HighlightResponseDto> = { error("unexpected create") }
    var recolorResult: (Int) -> Result<HighlightResponseDto> = { color ->
        Result.success(HighlightResponseDto("r", "kjv", emptyList(), color, "2026-10-23T10:00:00Z", "2026-10-23T10:00:01Z"))
    }
    var deleteResult: () -> Result<Unit> = { Result.success(Unit) }
    val creates = mutableListOf<Int>()
    val recolors = mutableListOf<Pair<String, Int>>()

    override suspend fun listHighlightsChangedSince(updatedSince: String?) = listResult
    override suspend fun createHighlight(versionId: String, verses: List<VerseLocationDto>, color: Int): Result<HighlightResponseDto> {
        creates += color
        return createResult(color)
    }
    override suspend fun recolorHighlight(id: String, color: Int): Result<HighlightResponseDto> {
        recolors += id to color
        return recolorResult(color).map { it.copy(id = id) }
    }
    override suspend fun deleteHighlight(id: String) = deleteResult()
}

private class FakeNoteApi : NoteSyncApi {
    var listResult: Result<NoteListResponseDto> = Result.success(NoteListResponseDto(emptyList(), "2026-10-23T10:00:00Z"))
    var createResult: (String) -> Result<NoteResponseDto> = { error("unexpected create") }
    val deletes = mutableListOf<String>()

    override suspend fun listNotesChangedSince(updatedSince: String?) = listResult
    override suspend fun createNote(versionId: String, verses: List<VerseLocationDto>, text: String) = createResult(text)
    override suspend fun updateNote(id: String, verses: List<VerseLocationDto>, text: String): Result<NoteResponseDto> =
        Result.success(NoteResponseDto(id, "kjv", verses, text, "2026-10-23T10:00:00Z", "2026-10-23T10:00:01Z"))
    override suspend fun deleteNote(id: String): Result<Unit> {
        deletes += id
        return Result.success(Unit)
    }
}

private class FakeReadingProgressApi : ReadingProgressSyncApi {
    data class Update(val bookId: Int, val chapter: Int, val verse: Int, val readAt: String)

    var getResult: Result<ReadingProgressResponseDto?> = Result.success(null)
    val updates = mutableListOf<Update>()

    override suspend fun getProgress() = getResult
    override suspend fun updateProgress(versionId: String, bookId: Int, chapter: Int, verse: Int, readAt: String): Result<ReadingProgressResponseDto> {
        updates += Update(bookId, chapter, verse, readAt)
        return Result.success(ReadingProgressResponseDto(versionId, bookId, chapter, verse, readAt))
    }
}

/** In-memory [SyncLocalStore] with the same compare-and-set rules as BibleDatabaseManager's SQL. */
private class FakeLocalStore : SyncLocalStore {
    val highlights = mutableListOf<Highlight>()
    val notes = mutableListOf<Note>()
    var highlightsWatermark: String? = null
    var notesWatermark: String? = null
    var reading = LocalReadingProgress(ReadingPosition(1, 1, 1), "kjv", changedAt = null, pushedAt = null, hasLocalPosition = false)

    private var nextId = 1L
    private var tick = 0

    /** A new local edit time each call, as isoTimestampNow() would give. */
    private fun now() = "2026-10-23T09:00:00.%03dZ".format(++tick)

    fun addHighlight(color: Int, remoteId: String? = null, deleted: Boolean = false): Highlight {
        val stamp = now()
        return Highlight(nextId++, remoteId, "kjv", listOf(VerseLocationDto(43, 3, 16)), color, stamp, stamp, if (deleted) stamp else null)
            .also { highlights += it }
    }

    fun addNote(text: String): Note {
        val stamp = now()
        return Note(nextId++, null, "kjv", listOf(VerseLocationDto(43, 3, 16)), text, stamp, stamp).also { notes += it }
    }

    fun recolor(localId: Long, color: Int) = updateHighlight(localId) { it.copy(color = color, updatedAt = now(), syncStatus = SyncStatus.PENDING) }
    fun restore(localId: Long) = updateHighlight(localId) { it.copy(deletedAt = null, updatedAt = now(), syncStatus = SyncStatus.PENDING) }
    fun softDeleteNote(localId: Long) = updateNote(localId) { val t = now(); it.copy(deletedAt = t, updatedAt = t, syncStatus = SyncStatus.PENDING) }

    private fun updateHighlight(localId: Long, change: (Highlight) -> Highlight) {
        val index = highlights.indexOfFirst { it.localId == localId }
        if (index >= 0) highlights[index] = change(highlights[index])
    }

    private fun updateNote(localId: Long, change: (Note) -> Note) {
        val index = notes.indexOfFirst { it.localId == localId }
        if (index >= 0) notes[index] = change(notes[index])
    }

    override suspend fun getPendingHighlights() = highlights.filter { it.syncStatus == SyncStatus.PENDING }

    override suspend fun markHighlightPushed(pushed: Highlight, remoteId: String, createdAt: String, updatedAt: String): Boolean {
        val current = highlights.first { it.localId == pushed.localId }
        val unchanged = current.updatedAt == pushed.updatedAt
        updateHighlight(pushed.localId) {
            if (unchanged) it.copy(remoteId = remoteId, createdAt = createdAt, updatedAt = updatedAt, syncStatus = SyncStatus.SYNCED)
            else it.copy(remoteId = remoteId)
        }
        return unchanged
    }

    override suspend fun purgeHighlight(pushed: Highlight) {
        val current = highlights.firstOrNull { it.localId == pushed.localId } ?: return
        if (current.updatedAt == pushed.updatedAt) highlights.remove(current) else updateHighlight(pushed.localId) { it.copy(remoteId = null) }
    }

    override suspend fun detachHighlight(pushed: Highlight) = updateHighlight(pushed.localId) { it.copy(remoteId = null, syncStatus = SyncStatus.PENDING) }

    override suspend fun deleteHighlightByRemoteId(remoteId: String) = highlights.removeAll { it.remoteId == remoteId }

    override suspend fun upsertHighlightFromServer(dto: HighlightResponseDto): Boolean {
        val existing = highlights.firstOrNull { it.remoteId == dto.id }
        if (existing == null) {
            highlights += Highlight(nextId++, dto.id, dto.versionId, dto.verses, dto.color, dto.createdAt, dto.updatedAt, null, SyncStatus.SYNCED)
            return true
        }
        if (existing.syncStatus == SyncStatus.PENDING) return false
        val fresh = existing.copy(versionId = dto.versionId, verses = dto.verses, color = dto.color, createdAt = dto.createdAt, updatedAt = dto.updatedAt, deletedAt = null)
        if (fresh == existing) return false
        updateHighlight(existing.localId) { fresh }
        return true
    }

    override suspend fun getPendingNotes() = notes.filter { it.syncStatus == SyncStatus.PENDING }

    override suspend fun markNotePushed(pushed: Note, remoteId: String, createdAt: String, updatedAt: String): Boolean {
        val current = notes.first { it.localId == pushed.localId }
        val unchanged = current.updatedAt == pushed.updatedAt
        updateNote(pushed.localId) {
            if (unchanged) it.copy(remoteId = remoteId, createdAt = createdAt, updatedAt = updatedAt, syncStatus = SyncStatus.SYNCED)
            else it.copy(remoteId = remoteId)
        }
        return unchanged
    }

    override suspend fun purgeNote(pushed: Note) {
        val current = notes.firstOrNull { it.localId == pushed.localId } ?: return
        if (current.updatedAt == pushed.updatedAt) notes.remove(current) else updateNote(pushed.localId) { it.copy(remoteId = null) }
    }

    override suspend fun detachNote(pushed: Note) = updateNote(pushed.localId) { it.copy(remoteId = null, syncStatus = SyncStatus.PENDING) }

    override suspend fun deleteNoteByRemoteId(remoteId: String) = notes.removeAll { it.remoteId == remoteId }

    override suspend fun upsertNoteFromServer(dto: NoteResponseDto): Boolean {
        val existing = notes.firstOrNull { it.remoteId == dto.id }
        if (existing == null) {
            notes += Note(nextId++, dto.id, dto.versionId, dto.verses, dto.text, dto.createdAt, dto.updatedAt, null, SyncStatus.SYNCED)
            return true
        }
        if (existing.syncStatus == SyncStatus.PENDING) return false
        val fresh = existing.copy(versionId = dto.versionId, verses = dto.verses, text = dto.text, createdAt = dto.createdAt, updatedAt = dto.updatedAt, deletedAt = null)
        if (fresh == existing) return false
        updateNote(existing.localId) { fresh }
        return true
    }

    override fun loadHighlightsSyncWatermark() = highlightsWatermark
    override fun saveHighlightsSyncWatermark(iso: String) { highlightsWatermark = iso }
    override fun loadNotesSyncWatermark() = notesWatermark
    override fun saveNotesSyncWatermark(iso: String) { notesWatermark = iso }

    override fun loadReadingProgress() = reading

    override fun markReadingPositionPushed(readAt: String) {
        if (reading.changedAt == readAt) reading = reading.copy(pushedAt = readAt)
    }

    override fun saveReadingPositionFromServer(server: ReadingProgressResponseDto, expectedChangedAt: String?): Boolean {
        if (reading.changedAt != expectedChangedAt) return false
        reading = LocalReadingProgress(
            ReadingPosition(server.bookId, server.chapter, server.verse), server.versionId,
            changedAt = server.updatedAt, pushedAt = server.updatedAt, hasLocalPosition = true
        )
        return true
    }
}
