package com.application.bibleapp.data.local

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.application.bibleapp.data.model.Highlight
import com.application.bibleapp.data.model.Note
import com.application.bibleapp.data.model.SyncStatus
import com.application.bibleapp.data.remote.VerseLocationDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SQL side of #83, on a real SQLite: a row changed between "read for push" and "mark pushed"
 * stays PENDING, and a pull never overwrites a PENDING row. Runs against the app's own database
 * (run it on the emulator only), on rows of a made-up version it removes afterwards.
 */
@RunWith(AndroidJUnit4::class)
class SyncCompareAndSetTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val verses = listOf(VerseLocationDto(43, 3, 16))

    @After
    fun removeTestRows() {
        val db = BibleDatabaseManager.getDatabase(context)
        db.execSQL("DELETE FROM highlights WHERE version_id = ?", arrayOf(TEST_VERSION))
        db.execSQL("DELETE FROM notes WHERE version_id = ?", arrayOf(TEST_VERSION))
    }

    @Test
    fun aHighlightRecoloredDuringItsUploadStaysPending() {
        val read = BibleDatabaseManager.insertHighlight(context, TEST_VERSION, verses, color = 1)
        laterMillisecond()
        BibleDatabaseManager.recolorHighlight(context, read.localId, color = 2)

        val marked = BibleDatabaseManager.markHighlightPushed(context, read.localId, read.updatedAt, "remote-1", SERVER_TIME, SERVER_TIME)

        assertFalse(marked)
        val row = highlight(read.localId)
        assertEquals(SyncStatus.PENDING, row.syncStatus)
        assertEquals("remote-1", row.remoteId)
        assertEquals(2, row.color)
    }

    @Test
    fun anUnchangedHighlightIsMarkedSynced() {
        val read = BibleDatabaseManager.insertHighlight(context, TEST_VERSION, verses, color = 1)

        assertTrue(BibleDatabaseManager.markHighlightPushed(context, read.localId, read.updatedAt, "remote-2", SERVER_TIME, SERVER_TIME))
        assertEquals(SyncStatus.SYNCED, highlight(read.localId).syncStatus)
    }

    @Test
    fun aHighlightDeletedDuringItsUploadKeepsTheDeleteForNextPass() {
        val read = BibleDatabaseManager.insertHighlight(context, TEST_VERSION, verses, color = 1)
        laterMillisecond()
        BibleDatabaseManager.softDeleteHighlight(context, read.localId)

        BibleDatabaseManager.markHighlightPushed(context, read.localId, read.updatedAt, "remote-3", SERVER_TIME, SERVER_TIME)

        val pending = BibleDatabaseManager.getPendingHighlights(context).single { it.localId == read.localId }
        assertEquals("remote-3", pending.remoteId)
        assertTrue(pending.deletedAt != null)
    }

    @Test
    fun aHighlightRestoredDuringItsDeleteIsKeptWithoutItsRemoteId() {
        val created = BibleDatabaseManager.insertHighlight(context, TEST_VERSION, verses, color = 1)
        BibleDatabaseManager.markHighlightPushed(context, created.localId, created.updatedAt, "remote-4", SERVER_TIME, SERVER_TIME)
        BibleDatabaseManager.softDeleteHighlight(context, created.localId)
        val readForDelete = highlight(created.localId)
        laterMillisecond()
        BibleDatabaseManager.restoreHighlight(context, readForDelete)

        BibleDatabaseManager.purgeHighlight(context, readForDelete.localId, readForDelete.updatedAt)

        val row = highlight(created.localId)
        assertNull(row.remoteId)
        assertNull(row.deletedAt)
        assertEquals(SyncStatus.PENDING, row.syncStatus)
    }

    @Test
    fun aPullDoesNotOverwriteAPendingHighlight() {
        val read = BibleDatabaseManager.insertHighlight(context, TEST_VERSION, verses, color = 5)
        BibleDatabaseManager.markHighlightPushed(context, read.localId, read.updatedAt, "remote-5", SERVER_TIME, SERVER_TIME)
        laterMillisecond()
        BibleDatabaseManager.recolorHighlight(context, read.localId, color = 6)

        val changed = BibleDatabaseManager.upsertHighlightFromServer(context, "remote-5", TEST_VERSION, verses, 5, SERVER_TIME, SERVER_TIME)

        assertFalse(changed)
        assertEquals(6, highlight(read.localId).color)
    }

    @Test
    fun aPullReportsAChangeOnlyWhenSomethingChanged() {
        assertTrue(BibleDatabaseManager.upsertHighlightFromServer(context, "remote-6", TEST_VERSION, verses, 1, SERVER_TIME, SERVER_TIME))
        assertFalse(BibleDatabaseManager.upsertHighlightFromServer(context, "remote-6", TEST_VERSION, verses, 1, SERVER_TIME, SERVER_TIME))
        assertTrue(BibleDatabaseManager.upsertHighlightFromServer(context, "remote-6", TEST_VERSION, verses, 2, SERVER_TIME, LATER_SERVER_TIME))
    }

    @Test
    fun aNoteEditedDuringItsUploadStaysPending() {
        val read = BibleDatabaseManager.insertNote(context, TEST_VERSION, verses, "First draft")
        laterMillisecond()
        BibleDatabaseManager.updateNote(context, read.localId, verses, "Second draft")

        assertFalse(BibleDatabaseManager.markNotePushed(context, read.localId, read.updatedAt, "note-1", SERVER_TIME, SERVER_TIME))
        val row = note(read.localId)
        assertEquals(SyncStatus.PENDING, row.syncStatus)
        assertEquals("note-1", row.remoteId)
        assertFalse(BibleDatabaseManager.upsertNoteFromServer(context, "note-1", TEST_VERSION, verses, "First draft", SERVER_TIME, SERVER_TIME))
        assertEquals("Second draft", note(read.localId).text)
    }

    private fun highlight(localId: Long): Highlight =
        BibleDatabaseManager.getPendingHighlights(context).firstOrNull { it.localId == localId }
            ?: BibleDatabaseManager.getAllActiveHighlights(context).single { it.localId == localId }

    private fun note(localId: Long): Note =
        BibleDatabaseManager.getPendingNotes(context).firstOrNull { it.localId == localId }
            ?: BibleDatabaseManager.getAllActiveNotes(context).single { it.localId == localId }

    /** Local edit times have millisecond precision; a person can't edit twice in one. */
    private fun laterMillisecond() = Thread.sleep(5)

    private companion object {
        const val TEST_VERSION = "test_sync_cas"
        const val SERVER_TIME = "2026-10-23T10:00:00.123456Z"
        const val LATER_SERVER_TIME = "2026-10-23T10:05:00Z"
    }
}
