package com.application.bibleapp.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.application.bibleapp.data.remote.HelloAoBibleDataSource
import com.application.bibleapp.data.remote.OurMannaBibleDataSource
import com.application.bibleapp.data.repository.AuthRepository
import com.application.bibleapp.data.repository.BibleRepository
import com.application.bibleapp.data.repository.HighlightRepository
import com.application.bibleapp.data.repository.NoteRepository
import com.application.bibleapp.data.repository.ReadingProgressRepository
import com.application.bibleapp.utils.isoTimestampNow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs a [SyncEngine] pass (see its doc for what a pass does) against the backend — see
 * docs/UI_Integration_Roadmap.md Phase E. No-ops entirely when signed out; nothing here ever
 * blocks the local-only experience, it just goes quiet until there's an account to sync to.
 *
 * The pass only counts as done ("Synced just now" in Settings) when it really reached the
 * server: a pass that couldn't throws, and WorkManager retries it with backoff (#80). Open
 * screens hear about the outcome through [SyncEvents] (#79).
 *
 * [syncMutex] serializes every run against every other one in this process — [SyncScheduler]
 * enqueues both a periodic job and an immediate one-off under two *different* unique work names,
 * so WorkManager itself doesn't dedupe them; without this, a periodic run's very first
 * (unscheduled-delay) execution can race the immediate trigger fired on the same app launch, and
 * two concurrent passes double-push the same PENDING rows — confirmed on-device: it produced two
 * server-side highlights (and, once pulled back, two local rows) for what should have been one.
 * A worker that arrives while another is already syncing just waits its turn rather than running
 * in parallel; by the time it gets the lock, the first pass has already cleared whatever it would
 * have redundantly pushed, so its own turn is a fast, mostly-empty no-op. Sign-out takes the same
 * lock ([AccountContentSync]), so a pass can't pull an account's data back in while it's removed.
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
            // Installs from before the content owner was recorded (#81) adopt the signed-in account.
            bibleRepository.adoptContentOwnerIfUnknown(authRepository.currentUserId)
            val engine = SyncEngine(bibleRepository, HighlightRepository(), NoteRepository(), ReadingProgressRepository())

            SyncEvents.passStarted()
            var outcome: SyncEngine.Outcome? = null
            val result = try {
                outcome = engine.runPass()
                // Drives Settings' "Synced Xm ago" status line (Phase G) — stamped only on a
                // pass that reached the server for everything (#80).
                bibleRepository.saveLastSyncCompletedAt(isoTimestampNow())
                bibleRepository.saveLastSyncFailed(false)
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed (attempt ${runAttemptCount + 1}): ${e.message}")
                when {
                    // The session ended mid-pass (its refresh token was refused): nothing to retry.
                    !authRepository.isLoggedIn -> Result.success()
                    runAttemptCount < MAX_RETRY_ATTEMPTS -> Result.retry()
                    else -> {
                        // Only flagged once retries are exhausted (Phase H) — a mid-retry attempt
                        // isn't a user-facing failure yet, it's still working through WorkManager's
                        // backoff, and flagging it here would flash "Sync failed" on transient
                        // hiccups a plain retry would've quietly recovered from.
                        bibleRepository.saveLastSyncFailed(true)
                        Result.failure()
                    }
                }
            } finally {
                SyncEvents.passFinished(outcome)
            }
            result
        }
    }

    internal companion object {
        private const val TAG = "SyncWorker"
        private const val MAX_RETRY_ATTEMPTS = 3
        val syncMutex = Mutex()
    }
}
