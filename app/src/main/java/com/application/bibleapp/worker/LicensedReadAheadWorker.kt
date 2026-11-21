package com.application.bibleapp.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.application.bibleapp.data.remote.HelloAoBibleDataSource
import com.application.bibleapp.data.remote.OurMannaBibleDataSource
import com.application.bibleapp.data.repository.BibleRepository
import com.application.bibleapp.data.repository.LicensedReadAhead
import com.application.bibleapp.data.repository.ReadAheadLimits
import com.application.bibleapp.data.repository.ReadAheadOutcome
import com.application.bibleapp.data.repository.ReadAheadPace
import com.application.bibleapp.data.repository.ReadAheadPaceStore
import com.application.bibleapp.data.repository.dailyVerseDateKey
import com.application.bibleapp.data.repository.readAheadOrder
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Reads a licensed version ahead in the background (#384), so the next chapters open offline:
 * the rest of the book the reader is in, then the next books, a chapter at a time with a pause
 * between them ([ReadAheadLimits]). Only on an unmetered network, at most [ReadAheadLimits.DAILY_CAP]
 * chapters a day, and after a 429 only once `Retry-After` has passed, at half the pace.
 *
 * Started by the reader each time it opens a chapter of a licensed version online
 * ([LicensedReadAheadScheduler.start]), which replaces any run under way so the read-ahead follows
 * the reader. Removing the version cancels it ([LicensedReadAheadScheduler.cancelFor]).
 */
class LicensedReadAheadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = PrefsReadAheadStore(applicationContext)
        val anchor = store.anchor() ?: return Result.success()
        val repository = BibleRepository(applicationContext, HelloAoBibleDataSource(), OurMannaBibleDataSource())
        if (!repository.canReadAhead(anchor.versionId)) return Result.success()
        val order = readAheadOrder(repository.getChapterStructure(anchor.versionId), anchor.bookId, anchor.chapter)
        val outcome = LicensedReadAhead(
            store = store,
            today = { dailyVerseDateKey() }, // the phone's local day
            now = System::currentTimeMillis,
            sleep = { delay(it) },
            isSaved = { book, chapter -> repository.hasSavedChapter(anchor.versionId, book, chapter) },
            isStillAdded = { repository.canReadAhead(anchor.versionId) },
            fetchAndSave = { book, chapter -> repository.fetchAndSaveLicensedChapter(anchor.versionId, book, chapter) }
        ).run(order, untilMs = System.currentTimeMillis() + ReadAheadLimits.RUN_MS)
        Log.i(TAG, "Read-ahead of ${anchor.versionId}: $outcome")
        if (outcome is ReadAheadOutcome.Paused) {
            LicensedReadAheadScheduler.continueAt(applicationContext, outcome.resumeAtMs)
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "LicensedReadAhead"
    }
}

/** Where the read-ahead starts from: the licensed chapter the reader opened last. */
data class ReadAheadAnchor(val versionId: String, val bookId: Int, val chapter: Int)

/** [ReadAheadPace] and [ReadAheadAnchor] in their own preferences file, so they outlive the job. */
internal class PrefsReadAheadStore(context: Context) : ReadAheadPaceStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): ReadAheadPace = ReadAheadPace(
        dayKey = prefs.getString(KEY_DAY, "").orEmpty(),
        fetchedToday = prefs.getInt(KEY_FETCHED_TODAY, 0),
        intervalMs = prefs.getLong(KEY_INTERVAL, ReadAheadLimits.START_INTERVAL_MS),
        lastFetchAtMs = prefs.getLong(KEY_LAST_FETCH, 0L),
        blockedUntilMs = prefs.getLong(KEY_BLOCKED_UNTIL, 0L)
    )

    // commit(), not apply(): the next chapter's wait is computed from this right away.
    override fun save(pace: ReadAheadPace) {
        prefs.edit()
            .putString(KEY_DAY, pace.dayKey)
            .putInt(KEY_FETCHED_TODAY, pace.fetchedToday)
            .putLong(KEY_INTERVAL, pace.intervalMs)
            .putLong(KEY_LAST_FETCH, pace.lastFetchAtMs)
            .putLong(KEY_BLOCKED_UNTIL, pace.blockedUntilMs)
            .commit()
    }

    fun anchor(): ReadAheadAnchor? {
        val versionId = prefs.getString(KEY_ANCHOR_VERSION, null) ?: return null
        return ReadAheadAnchor(versionId, prefs.getInt(KEY_ANCHOR_BOOK, 1), prefs.getInt(KEY_ANCHOR_CHAPTER, 1))
    }

    fun setAnchor(anchor: ReadAheadAnchor?) {
        prefs.edit().apply {
            if (anchor == null) {
                remove(KEY_ANCHOR_VERSION).remove(KEY_ANCHOR_BOOK).remove(KEY_ANCHOR_CHAPTER)
            } else {
                putString(KEY_ANCHOR_VERSION, anchor.versionId)
                putInt(KEY_ANCHOR_BOOK, anchor.bookId)
                putInt(KEY_ANCHOR_CHAPTER, anchor.chapter)
            }
        }.apply() // called on the main thread by the reader; the job reads it in the same process
    }

    private companion object {
        const val PREFS_NAME = "licensed_read_ahead"
        const val KEY_DAY = "day"
        const val KEY_FETCHED_TODAY = "fetched_today"
        const val KEY_INTERVAL = "interval_ms"
        const val KEY_LAST_FETCH = "last_fetch_at"
        const val KEY_BLOCKED_UNTIL = "blocked_until"
        const val KEY_ANCHOR_VERSION = "anchor_version"
        const val KEY_ANCHOR_BOOK = "anchor_book"
        const val KEY_ANCHOR_CHAPTER = "anchor_chapter"
    }
}

/** Starts, continues and cancels [LicensedReadAheadWorker] (#384). One job for the whole phone. */
object LicensedReadAheadScheduler {
    private const val WORK_NAME = "licensed_read_ahead"

    // Wi-Fi and other unmetered networks only: never the reader's mobile data.
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

    /** The reader opened [bookId] [chapter] of the licensed [versionId] online: read ahead from there. */
    fun start(context: Context, versionId: String, bookId: Int, chapter: Int) {
        PrefsReadAheadStore(context).setAnchor(ReadAheadAnchor(versionId, bookId, chapter))
        val request = OneTimeWorkRequestBuilder<LicensedReadAheadWorker>().setConstraints(constraints).build()
        // REPLACE: a run reading ahead from an older chapter gives way to this one. The saved pace
        // keeps the restarted run from fetching any sooner than the old one would have.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** A run ended early (its time, the pace, a 429): the next one starts at [resumeAtMs]. */
    internal fun continueAt(context: Context, resumeAtMs: Long) {
        val request = OneTimeWorkRequestBuilder<LicensedReadAheadWorker>()
            .setConstraints(constraints)
            .setInitialDelay((resumeAtMs - System.currentTimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
        // APPEND_OR_REPLACE: queued after the run that's ending (still running while it calls this).
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** The reader got a 429 too: the read-ahead waits for `Retry-After` and slows down as well. */
    fun noteBusy(context: Context, retryAfterSeconds: Long?) {
        val store = PrefsReadAheadStore(context)
        store.save(store.load().afterBusy(System.currentTimeMillis(), retryAfterSeconds))
    }

    /** [versionId] was removed from the phone: stop reading it ahead. */
    fun cancelFor(context: Context, versionId: String) {
        val store = PrefsReadAheadStore(context)
        if (store.anchor()?.versionId != versionId) return
        store.setAnchor(null)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
