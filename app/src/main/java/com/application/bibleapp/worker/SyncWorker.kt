package com.application.bibleapp.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.application.bibleapp.data.model.Highlight
import com.application.bibleapp.data.model.Note
import com.application.bibleapp.data.remote.HelloAoBibleDataSource
import com.application.bibleapp.data.remote.OurMannaBibleDataSource
import com.application.bibleapp.data.repository.AuthRepository
import com.application.bibleapp.data.repository.BibleRepository
import com.application.bibleapp.data.repository.HighlightRepository
import com.application.bibleapp.data.repository.NoteRepository
import com.application.bibleapp.data.repository.ReadingProgressRepository
import com.application.bibleapp.utils.isoTimestampNow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reconciles the local-first highlight/note/reading-progress tables (see
 * data/local/BibleDatabaseManager.kt, Phase A) with the backend — see
 * docs/UI_Integration_Roadmap.md Phase E. No-ops entirely when signed out; nothing here ever
 * blocks the local-only experience, it just goes quiet until there's an account to sync to.
 *
 * Each sync pass, per resource (highlights, notes):
 * 1. **Push** every PENDING row — a still-local create/edit goes out as a create/PATCH-or-PUT, a
 *    soft-deleted row goes out as a DELETE (or is just purged locally if it was never pushed to
 *    begin with, i.e. created and deleted again before ever syncing). A single item's failure
 *    (network hiccup, etc.) leaves it PENDING for the next pass rather than failing the whole run
 *    — see the `onSuccess`-only handling below.
 * 2. **Pull** `updatedSince` the last successful pull (null on the very first sync, which pulls
 *    everything active), upserting by the server's id — matches an existing local row if this
 *    device already had one (either its own, just confirmed, or a previous pull), otherwise
 *    inserts a new row for something created on another device. A pulled tombstone
 *    (`deletedAt != null`) removes this device's copy if it has one.
 *
 * Push always runs before pull, so by the time of the pull, whatever this device just pushed is
 * already server-authoritative — no local/server timestamp comparison is needed on the pull side,
 * only on create/update ordering, which the server itself owns (contract decision 18).
 *
 * Reading progress has no local table of its own to reconcile (it's a SharedPreferences-backed
 * singleton — see BibleRepository.saveReadingPosition/loadReadingPosition, already pushed
 * opportunistically on every chapter change by BibleViewModel.loadChapter). This worker's part is
 * just a safety-net push (in case that opportunistic push missed a window with no connectivity)
 * and a pull that updates the stored position only — never the live reading UI, since jumping
 * a screen out from under someone actively reading would be jarring; the new position takes
 * effect next time the app cold-starts or the user navigates there themselves.
 *
 * [syncMutex] serializes every run against every other one in this process — [SyncScheduler]
 * enqueues both a periodic job and an immediate one-off under two *different* unique work names,
 * so WorkManager itself doesn't dedupe them; without this, a periodic run's very first
 * (unscheduled-delay) execution can race the immediate trigger fired on the same app launch, and
 * two concurrent passes double-push the same PENDING rows — confirmed on-device: it produced two
 * server-side highlights (and, once pulled back, two local rows) for what should have been one.
 * A worker that arrives while another is already syncing just waits its turn rather than running
 * in parallel; by the time it gets the lock, the first pass has already cleared whatever it would
 * have redundantly pushed, so its own turn is a fast, mostly-empty no-op.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "doWork() invoked (runAttemptCount=$runAttemptCount)")
        return syncMutex.withLock {
            val authRepository = AuthRepository(applicationContext)
            Log.i(TAG, "isLoggedIn=${authRepository.isLoggedIn}")
            if (!authRepository.isLoggedIn) return@withLock Result.success()

            val bibleRepository = BibleRepository(
                context = applicationContext,
                remote = HelloAoBibleDataSource(),
                daily = OurMannaBibleDataSource()
            )
            val highlightRepository = HighlightRepository()
            val noteRepository = NoteRepository()
            val readingProgressRepository = ReadingProgressRepository()

            val outcome = runCatching {
                pushHighlights(bibleRepository, highlightRepository)
                pullHighlights(bibleRepository, highlightRepository)
                pushNotes(bibleRepository, noteRepository)
                pullNotes(bibleRepository, noteRepository)
                syncReadingProgress(bibleRepository, readingProgressRepository)
            }

            outcome.fold(
                onSuccess = {
                    // Drives Settings' "Synced Xm ago" status line (Phase G) — stamped only on a
                    // fully successful pass, not a partial one, so the displayed time never
                    // claims content synced that a mid-pass failure actually left PENDING.
                    bibleRepository.saveLastSyncCompletedAt(isoTimestampNow())
                    Result.success()
                },
                onFailure = { e ->
                    Log.w(TAG, "Sync failed (attempt ${runAttemptCount + 1}): ${e.message}")
                    if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
                }
            )
        }
    }

    private suspend fun pushHighlights(bibleRepository: BibleRepository, remote: HighlightRepository) {
        bibleRepository.getPendingHighlights().forEach { highlight -> pushHighlight(bibleRepository, remote, highlight) }
    }

    private suspend fun pushHighlight(bibleRepository: BibleRepository, remote: HighlightRepository, highlight: Highlight) {
        val remoteId = highlight.remoteId
        if (highlight.deletedAt != null) {
            if (remoteId == null) {
                bibleRepository.purgeHighlight(highlight.localId)
            } else {
                remote.deleteHighlight(remoteId).onSuccess { bibleRepository.purgeHighlight(highlight.localId) }
            }
            return
        }
        if (remoteId == null) {
            remote.createHighlight(highlight.versionId, highlight.verses, highlight.color).onSuccess { dto ->
                bibleRepository.markHighlightPushed(highlight.localId, dto.id, dto.createdAt, dto.updatedAt)
            }
        } else {
            remote.recolorHighlight(remoteId, highlight.color).onSuccess { dto ->
                bibleRepository.markHighlightPushed(highlight.localId, dto.id, dto.createdAt, dto.updatedAt)
            }
        }
    }

    private suspend fun pullHighlights(bibleRepository: BibleRepository, remote: HighlightRepository) {
        val watermark = bibleRepository.loadHighlightsSyncWatermark()
        val startedAt = isoTimestampNow()
        remote.listHighlights(updatedSince = watermark).onSuccess { dtos ->
            dtos.forEach { dto ->
                if (dto.deletedAt != null) {
                    bibleRepository.deleteHighlightByRemoteId(dto.id)
                } else {
                    bibleRepository.upsertHighlightFromServer(
                        dto.id, dto.versionId, dto.verses, dto.color, dto.createdAt, dto.updatedAt
                    )
                }
            }
            bibleRepository.saveHighlightsSyncWatermark(startedAt)
        }
    }

    private suspend fun pushNotes(bibleRepository: BibleRepository, remote: NoteRepository) {
        bibleRepository.getPendingNotes().forEach { note -> pushNote(bibleRepository, remote, note) }
    }

    private suspend fun pushNote(bibleRepository: BibleRepository, remote: NoteRepository, note: Note) {
        val remoteId = note.remoteId
        if (note.deletedAt != null) {
            if (remoteId == null) {
                bibleRepository.purgeNote(note.localId)
            } else {
                remote.deleteNote(remoteId).onSuccess { bibleRepository.purgeNote(note.localId) }
            }
            return
        }
        if (remoteId == null) {
            remote.createNote(note.versionId, note.verses, note.text).onSuccess { dto ->
                bibleRepository.markNotePushed(note.localId, dto.id, dto.createdAt, dto.updatedAt)
            }
        } else {
            remote.updateNote(remoteId, note.verses, note.text).onSuccess { dto ->
                bibleRepository.markNotePushed(note.localId, dto.id, dto.createdAt, dto.updatedAt)
            }
        }
    }

    private suspend fun pullNotes(bibleRepository: BibleRepository, remote: NoteRepository) {
        val watermark = bibleRepository.loadNotesSyncWatermark()
        val startedAt = isoTimestampNow()
        remote.listNotes(updatedSince = watermark).onSuccess { dtos ->
            dtos.forEach { dto ->
                if (dto.deletedAt != null) {
                    bibleRepository.deleteNoteByRemoteId(dto.id)
                } else {
                    bibleRepository.upsertNoteFromServer(
                        dto.id, dto.versionId, dto.verses, dto.text, dto.createdAt, dto.updatedAt
                    )
                }
            }
            bibleRepository.saveNotesSyncWatermark(startedAt)
        }
    }

    private suspend fun syncReadingProgress(bibleRepository: BibleRepository, remote: ReadingProgressRepository) {
        val versionId = bibleRepository.loadSelectedVersion()
        val position = bibleRepository.loadReadingPosition()
        remote.updateProgress(versionId, position.bookId, position.chapter, position.verse)

        remote.getProgress().onSuccess { server ->
            if (server != null) {
                bibleRepository.saveReadingPosition(server.bookId, server.chapter, server.verse)
            }
        }
    }

    private companion object {
        const val TAG = "SyncWorker"
        const val MAX_RETRY_ATTEMPTS = 3
        val syncMutex = Mutex()
    }
}
