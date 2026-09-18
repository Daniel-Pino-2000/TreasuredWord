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
import java.util.concurrent.TimeUnit

/**
 * Schedules [SyncWorker] — see docs/UI_Integration_Roadmap.md Phase E. Unlike the daily-verse
 * jobs (wall-clock-anchored one-time requests that re-enqueue themselves), sync just needs to
 * happen regularly while there's a network connection; a [androidx.work.PeriodicWorkRequest] at
 * WorkManager's minimum interval (15 minutes — there's no way to go shorter) fits that directly.
 */
object SyncScheduler {
    private const val PERIODIC_WORK_NAME = "content_sync_periodic"
    private const val IMMEDIATE_WORK_NAME = "content_sync_immediate"
    private const val PERIODIC_INTERVAL_MINUTES = 15L

    /** Ensures the periodic sync job is running. Call from app startup — [ExistingPeriodicWorkPolicy.KEEP]
     *  so this never disturbs a cycle already in progress; it only fills in a job an OEM battery
     *  manager silently dropped, the same reasoning as [DailyVerseScheduler.scheduleDailyFetch]. */
    fun schedulePeriodicSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** A one-off sync right now, superseding any not-yet-run immediate request — used right after
     *  app start and right after a successful login/register (see Navigation.kt), so a fresh
     *  sign-in doesn't wait up to [PERIODIC_INTERVAL_MINUTES] to pull what's already on the
     *  account. Also what a future "Sync now" button (Phase G) would call.
     *
     *  [setExpedited] matters here, not just the naming: a plain (non-expedited) OneTimeWorkRequest
     *  is still subject to normal JobScheduler deferral — confirmed on-device, a request enqueued
     *  right after a fresh install/login sat unrun for over a minute with no expedited flag, which
     *  defeats the entire point of a "sync right now" call. `RUN_AS_NON_EXPEDITED_WORK_REQUEST`
     *  falls back to ordinary (deferred) scheduling only if the app's expedited-job quota is
     *  exhausted, rather than failing outright. */
    fun triggerImmediateSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(IMMEDIATE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
