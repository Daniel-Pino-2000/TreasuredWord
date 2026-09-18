package com.application.bibleapp.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.application.bibleapp.data.remote.HelloAoBibleDataSource
import com.application.bibleapp.data.remote.OurMannaBibleDataSource
import com.application.bibleapp.data.repository.BibleRepository
import java.util.concurrent.TimeUnit

/**
 * Schedules [SyncWorker] — see docs/UI_Integration_Roadmap.md Phase E/G. Unlike the daily-verse
 * jobs (wall-clock-anchored one-time requests that re-enqueue themselves), sync just needs to
 * happen regularly while there's a network connection; a [androidx.work.PeriodicWorkRequest] at
 * WorkManager's minimum interval (15 minutes — there's no way to go shorter) fits that directly.
 *
 * Both [schedulePeriodicSync] and [triggerImmediateSync] read the Wi-Fi-only preference (Settings'
 * Sync section, Phase G) themselves rather than taking it as a parameter, so every call site
 * — app startup, post-login, and a future "Sync now" button — automatically respects whatever
 * the user last chose without each one having to look it up.
 */
object SyncScheduler {
    private const val PERIODIC_WORK_NAME = "content_sync_periodic"
    private const val IMMEDIATE_WORK_NAME = "content_sync_immediate"
    private const val PERIODIC_INTERVAL_MINUTES = 15L

    private fun currentConstraints(context: Context): Constraints {
        val repository = BibleRepository(context, HelloAoBibleDataSource(), OurMannaBibleDataSource())
        val networkType = if (repository.isWifiOnlySyncEnabled()) NetworkType.UNMETERED else NetworkType.CONNECTED
        return Constraints.Builder().setRequiredNetworkType(networkType).build()
    }

    /** Ensures the periodic sync job is running with the current auto-sync/Wi-Fi-only
     *  preferences — call from app startup and whenever either preference changes.
     *  [ExistingPeriodicWorkPolicy.UPDATE] applies new constraints to an already-scheduled job
     *  without resetting its cadence or cancelling a run in progress, unlike KEEP (which would
     *  silently ignore a constraint change) or REPLACE (which would cancel an in-flight run). */
    fun schedulePeriodicSync(context: Context) {
        val repository = BibleRepository(context, HelloAoBibleDataSource(), OurMannaBibleDataSource())
        if (!repository.isAutoSyncEnabled()) {
            cancelPeriodicSync(context)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(currentConstraints(context))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Stops the periodic job — called when the user turns Auto-sync off. A manual "Sync now"
     *  (Phase G) still works while this is off; only the background cadence stops. */
    fun cancelPeriodicSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    /** A one-off sync right now, superseding any not-yet-run immediate request — used right after
     *  app start and right after a successful login/register (see Navigation.kt), and by
     *  Settings' manual "Sync now" button (Phase G). Runs even if Auto-sync is off — that
     *  preference only governs the periodic background cadence, not an explicit user request.
     *
     *  [setExpedited] matters here, not just the naming: a plain (non-expedited) OneTimeWorkRequest
     *  is still subject to normal JobScheduler deferral — confirmed on-device, a request enqueued
     *  right after a fresh install/login sat unrun for over a minute with no expedited flag, which
     *  defeats the entire point of a "sync right now" call. `RUN_AS_NON_EXPEDITED_WORK_REQUEST`
     *  falls back to ordinary (deferred) scheduling only if the app's expedited-job quota is
     *  exhausted, rather than failing outright. */
    fun triggerImmediateSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(currentConstraints(context))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(IMMEDIATE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
