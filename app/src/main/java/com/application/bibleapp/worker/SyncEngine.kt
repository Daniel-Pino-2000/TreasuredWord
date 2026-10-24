package com.application.bibleapp.worker

import android.util.Log
import com.application.bibleapp.data.model.Highlight
import com.application.bibleapp.data.model.Note
import com.application.bibleapp.data.remote.BackendHttpException
import com.application.bibleapp.data.remote.HighlightListResponseDto
import com.application.bibleapp.data.remote.HighlightResponseDto
import com.application.bibleapp.data.remote.NoteListResponseDto
import com.application.bibleapp.data.remote.NoteResponseDto
import com.application.bibleapp.data.remote.ReadingProgressResponseDto
import com.application.bibleapp.data.remote.VerseLocationDto
import com.application.bibleapp.data.remote.isRejectedByServer
import com.application.bibleapp.data.repository.ReadingPosition
import com.application.bibleapp.utils.formatIsoTimestamp
import com.application.bibleapp.utils.parseIsoTimestamp
import kotlin.coroutines.cancellation.CancellationException

/** The backend calls a sync pass makes for highlights — implemented by HighlightRepository. */
interface HighlightSyncApi {
    suspend fun listHighlightsChangedSince(updatedSince: String?): Result<HighlightListResponseDto>
    suspend fun createHighlight(versionId: String, verses: List<VerseLocationDto>, color: Int): Result<HighlightResponseDto>
    suspend fun recolorHighlight(id: String, color: Int): Result<HighlightResponseDto>
    suspend fun deleteHighlight(id: String): Result<Unit>
}

/** The backend calls a sync pass makes for notes — implemented by NoteRepository. */
interface NoteSyncApi {
    suspend fun listNotesChangedSince(updatedSince: String?): Result<NoteListResponseDto>
    suspend fun createNote(versionId: String, verses: List<VerseLocationDto>, text: String): Result<NoteResponseDto>
    suspend fun updateNote(id: String, verses: List<VerseLocationDto>, text: String): Result<NoteResponseDto>
    suspend fun deleteNote(id: String): Result<Unit>
}

/** Implemented by ReadingProgressRepository. */
interface ReadingProgressSyncApi {
    suspend fun getProgress(): Result<ReadingProgressResponseDto?>
    suspend fun updateProgress(versionId: String, bookId: Int, chapter: Int, verse: Int, readAt: String): Result<ReadingProgressResponseDto>
}

/**
 * The stored reading position plus when it last changed on this device ([changedAt]) and the last
 * [changedAt] the server accepted ([pushedAt]) — see [SyncEngine]'s reading-progress notes (#82).
 * [hasLocalPosition] is false until the person has moved away from the default Genesis 1.
 */
data class LocalReadingProgress(
    val position: ReadingPosition,
    val versionId: String,
    val changedAt: String?,
    val pushedAt: String?,
    val hasLocalPosition: Boolean
)

/**
 * This device's side of a sync pass — implemented by BibleRepository. Every "mark"/"purge" takes
 * the row as it was read before its push, and only acts if the row is still exactly that (#83).
 */
interface SyncLocalStore {
    suspend fun getPendingHighlights(): List<Highlight>
    /** Marks [pushed] SYNCED with the server's id and timestamps — unless it changed while it was
     *  being sent, in which case it only stores [remoteId] and stays PENDING for the next pass.
     *  True if it was marked SYNCED. */
    suspend fun markHighlightPushed(pushed: Highlight, remoteId: String, createdAt: String, updatedAt: String): Boolean
    /** Removes [pushed] once its delete reached the server (or it never did and doesn't need
     *  to) — unless it was restored meanwhile, in which case it's re-created next pass. */
    suspend fun purgeHighlight(pushed: Highlight)
    /** The server doesn't have [pushed]'s remote id (404): drop the id so the next pass creates it. */
    suspend fun detachHighlight(pushed: Highlight)
    /** True if a row was removed. */
    suspend fun deleteHighlightByRemoteId(remoteId: String): Boolean
    /** True if this device's copy changed. Leaves a row with unpushed local changes alone. */
    suspend fun upsertHighlightFromServer(dto: HighlightResponseDto): Boolean

    suspend fun getPendingNotes(): List<Note>
    suspend fun markNotePushed(pushed: Note, remoteId: String, createdAt: String, updatedAt: String): Boolean
    suspend fun purgeNote(pushed: Note)
    suspend fun detachNote(pushed: Note)
    suspend fun deleteNoteByRemoteId(remoteId: String): Boolean
    suspend fun upsertNoteFromServer(dto: NoteResponseDto): Boolean

    fun loadHighlightsSyncWatermark(): String?
    fun saveHighlightsSyncWatermark(iso: String)
    fun loadNotesSyncWatermark(): String?
    fun saveNotesSyncWatermark(iso: String)

    fun loadReadingProgress(): LocalReadingProgress
    /** Records that the server accepted the position changed at [readAt], if it's still current. */
    fun markReadingPositionPushed(readAt: String)
    /** Stores the server's position, if the local one is still the one changed at
     *  [expectedChangedAt] (it wasn't moved while the request ran). True if stored. */
    fun saveReadingPositionFromServer(server: ReadingProgressResponseDto, expectedChangedAt: String?): Boolean
}

/** A sync pass couldn't reach the server (or the server failed): the whole pass is retried (#80). */
class SyncFailedException(what: String, cause: Throwable) : Exception("$what failed: ${cause.message}", cause)

/**
 * How far before the server's clock the next pull starts. A change saved at the same moment as a
 * pull can land just before the server time that pull reports; re-pulling the last minute is
 * cheap, since the upserts don't change anything that's already current (#84).
 */
internal const val WATERMARK_SAFETY_MARGIN_MS = 60_000L

/** The next pull's `updatedSince`: the server's clock from this pull, or (from a server too old
 *  to send one) the phone's clock when this pull started — less [WATERMARK_SAFETY_MARGIN_MS]. */
internal fun nextSyncWatermark(serverTime: String?, requestedAtMillis: Long): String {
    val base = serverTime?.let(::parseIsoTimestamp) ?: requestedAtMillis
    return formatIsoTimestamp(base - WATERMARK_SAFETY_MARGIN_MS)
}

/** Whether a position the server has should replace this device's (#82): yes unless this device
 *  moved at or after the time the server's was read. A device with no recorded change time
 *  (installed before it was kept) defers to the server, as it always did. */
internal fun serverPositionWins(serverUpdatedAt: String, localChangedAt: String?): Boolean {
    val serverAt = parseIsoTimestamp(serverUpdatedAt) ?: return false
    val localAt = localChangedAt?.let(::parseIsoTimestamp) ?: return true
    return serverAt > localAt
}

/**
 * One sync pass: reconciles the local-first highlight/note tables (see BibleDatabaseManager) and
 * the stored reading position with the backend. [SyncWorker] runs it; kept apart from the worker
 * so it can be tested without Android (see SyncEngineTest).
 *
 * Per resource (highlights, then notes):
 * 1. **Push** every PENDING row — a create, a recolor/edit, or a DELETE for a soft-deleted row
 *    (a row deleted before it was ever pushed is just purged). A row is only marked SYNCED if it
 *    didn't change while its request was out; otherwise it stays PENDING with its new remote id
 *    and goes out again next pass (#83).
 * 2. **Pull** everything changed since the last pull, matched by the server's id. A pulled
 *    tombstone removes this device's copy. A row with unpushed local changes isn't overwritten
 *    (its own push failed or it changed mid-push); it's pushed next pass, and the pull after
 *    that brings back the server's result. A delete from another device still wins.
 *
 * Failures (#80): the server refusing one item (a 4xx) is that item's problem — it's logged and
 * left PENDING (or, for a 404, re-created/purged) without holding up the rest. Anything else —
 * no connection, a timeout, a 5xx, a failed pull — means the server couldn't be reached, so the
 * pass throws [SyncFailedException] and the worker retries it instead of reporting "Synced".
 *
 * Reading progress (#82): the position is pushed only when it changed on this device since the
 * last accepted push, stamped with the time it changed — never "now", or a device that hasn't
 * been read on for days would overwrite newer progress from another one. An untouched default
 * position is never pushed. The server's position replaces the local one only if it's newer.
 */
class SyncEngine(
    private val local: SyncLocalStore,
    private val highlights: HighlightSyncApi,
    private val notes: NoteSyncApi,
    private val readingProgress: ReadingProgressSyncApi,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.w(TAG, it) }
) {
    /** [contentChanged]: the pass changed this device's highlights or notes. [restoredReadingPosition]:
     *  the server's position, stored on a device that hadn't been read on yet. */
    data class Outcome(val contentChanged: Boolean, val restoredReadingPosition: ReadingPosition?)

    suspend fun runPass(): Outcome {
        pushHighlights()
        val highlightsChanged = pullHighlights()
        pushNotes()
        val notesChanged = pullNotes()
        val restored = syncReadingProgress()
        return Outcome(highlightsChanged || notesChanged, restored)
    }

    /** Just the pushes — sign-out uses this to upload what it can before removing it (#81).
     *  Throws [SyncFailedException] if the server can't be reached. */
    suspend fun pushPending() {
        pushHighlights()
        pushNotes()
    }

    private suspend fun pushHighlights() {
        local.getPendingHighlights().forEach { pushHighlight(it) }
    }

    private suspend fun pushHighlight(highlight: Highlight) {
        val remoteId = highlight.remoteId
        when {
            highlight.deletedAt != null && remoteId == null -> local.purgeHighlight(highlight)
            highlight.deletedAt != null -> highlights.deleteHighlight(remoteId!!).handleItem(
                "highlight delete", highlight.localId,
                onSuccess = { local.purgeHighlight(highlight) },
                // Already gone from the server, which is what the delete wanted.
                onNotFound = { local.purgeHighlight(highlight) }
            )
            remoteId == null -> highlights.createHighlight(highlight.versionId, highlight.verses, highlight.color).handleItem(
                "highlight create", highlight.localId,
                onSuccess = { dto -> local.markHighlightPushed(highlight, dto.id, dto.createdAt, dto.updatedAt) }
            )
            else -> highlights.recolorHighlight(remoteId, highlight.color).handleItem(
                "highlight recolor", highlight.localId,
                onSuccess = { dto -> local.markHighlightPushed(highlight, dto.id, dto.createdAt, dto.updatedAt) },
                onNotFound = { local.detachHighlight(highlight) }
            )
        }
    }

    private suspend fun pullHighlights(): Boolean {
        val requestedAt = clock()
        val page = highlights.listHighlightsChangedSince(local.loadHighlightsSyncWatermark()).orFailPass("highlight pull")
        var changed = false
        page.highlights.forEach { dto ->
            val rowChanged = if (dto.deletedAt != null) local.deleteHighlightByRemoteId(dto.id) else local.upsertHighlightFromServer(dto)
            changed = changed || rowChanged
        }
        local.saveHighlightsSyncWatermark(nextSyncWatermark(page.serverTime, requestedAt))
        return changed
    }

    private suspend fun pushNotes() {
        local.getPendingNotes().forEach { pushNote(it) }
    }

    private suspend fun pushNote(note: Note) {
        val remoteId = note.remoteId
        when {
            note.deletedAt != null && remoteId == null -> local.purgeNote(note)
            note.deletedAt != null -> notes.deleteNote(remoteId!!).handleItem(
                "note delete", note.localId,
                onSuccess = { local.purgeNote(note) },
                onNotFound = { local.purgeNote(note) }
            )
            remoteId == null -> notes.createNote(note.versionId, note.verses, note.text).handleItem(
                "note create", note.localId,
                onSuccess = { dto -> local.markNotePushed(note, dto.id, dto.createdAt, dto.updatedAt) }
            )
            else -> notes.updateNote(remoteId, note.verses, note.text).handleItem(
                "note update", note.localId,
                onSuccess = { dto -> local.markNotePushed(note, dto.id, dto.createdAt, dto.updatedAt) },
                onNotFound = { local.detachNote(note) }
            )
        }
    }

    private suspend fun pullNotes(): Boolean {
        val requestedAt = clock()
        val page = notes.listNotesChangedSince(local.loadNotesSyncWatermark()).orFailPass("note pull")
        var changed = false
        page.notes.forEach { dto ->
            val rowChanged = if (dto.deletedAt != null) local.deleteNoteByRemoteId(dto.id) else local.upsertNoteFromServer(dto)
            changed = changed || rowChanged
        }
        local.saveNotesSyncWatermark(nextSyncWatermark(page.serverTime, requestedAt))
        return changed
    }

    private suspend fun syncReadingProgress(): ReadingPosition? {
        val before = local.loadReadingProgress()
        val changedAt = before.changedAt
        if (changedAt != null && changedAt != before.pushedAt) {
            val position = before.position
            readingProgress.updateProgress(before.versionId, position.bookId, position.chapter, position.verse, readAt = changedAt)
                .handleItem(
                    "reading progress push", localId = 0,
                    onSuccess = { local.markReadingPositionPushed(changedAt) },
                    // The server will never take this one (e.g. a verse it considers invalid);
                    // stop resending it.
                    onRejected = { local.markReadingPositionPushed(changedAt) }
                )
        }

        val server = readingProgress.getProgress().orFailPass("reading progress pull") ?: return null
        val current = local.loadReadingProgress()
        if (!serverPositionWins(server.updatedAt, current.changedAt)) return null
        val stored = local.saveReadingPositionFromServer(server, expectedChangedAt = current.changedAt)
        return if (stored && !current.hasLocalPosition) ReadingPosition(server.bookId, server.chapter, server.verse) else null
    }

    private fun <T> Result<T>.orFailPass(what: String): T = getOrElse { error ->
        if (error is CancellationException) throw error
        throw SyncFailedException(what, error)
    }

    /** One item's push result: success, the server refusing just this item (logged, kept for
     *  later or handled by [onNotFound]/[onRejected]), or a failure that fails the pass. */
    private suspend fun <T> Result<T>.handleItem(
        what: String,
        localId: Long,
        onSuccess: suspend (T) -> Unit,
        onNotFound: (suspend () -> Unit)? = null,
        onRejected: (suspend () -> Unit)? = null
    ) {
        val error = exceptionOrNull() ?: return onSuccess(getOrThrow())
        if (error is CancellationException) throw error
        if (!error.isRejectedByServer) throw SyncFailedException(what, error)
        log("Push $what (local_id=$localId) refused: ${error.message}")
        if (onNotFound != null && (error as BackendHttpException).status == 404) onNotFound() else onRejected?.invoke()
    }
}

private const val TAG = "SyncEngine"
