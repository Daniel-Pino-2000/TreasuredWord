package com.application.bibleapp.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules both background jobs as self-rescheduling one-time work requests anchored
 * to a specific time of day, rather than [androidx.work.PeriodicWorkRequest] — periodic
 * requests can't be pinned to a wall-clock time like "00:05" or "the user's chosen
 * time," only to an interval, and drift over repeated runs. Each worker re-enqueues
 * its own next run when it finishes — see [DailyVerseFetchWorker] and
 * [DailyVerseNotificationWorker].
 *
 * Deliberately WorkManager rather than exact alarms for both jobs: neither needs
 * to-the-minute precision (a devotional notification landing a few minutes late is
 * fine), and avoiding AlarmManager's exact-alarm APIs means no SCHEDULE_EXACT_ALARM
 * special permission, no user trip to system Settings to grant it, and none of the
 * extra Play Store policy scrutiny exact alarms attract.
 */
object DailyVerseScheduler {
    const val NOTIFICATION_CHANNEL_ID = "daily_verse"

    private const val FETCH_WORK_NAME = "daily_verse_fetch"
    private const val NOTIFICATION_WORK_NAME = "daily_verse_notification"

    // A few minutes after midnight, not exactly 00:00 — gives the day boundary a
    // moment to settle, and comfortably precedes the earliest notification time any
    // user would realistically pick. Local midnight, although OurManna switches its verse
    // at about 00:00 UTC (#298): a verse fetched before its switchover that repeats
    // yesterday's is replaced by the curated one (BibleRepository.verseToKeepForToday).
    private const val FETCH_HOUR = 0
    private const val FETCH_MINUTE = 5

    /**
     * Ensures the daily fetch chain is running. [policy] defaults to [ExistingWorkPolicy.KEEP]
     * for the normal "make sure this is set up" call from app startup — that must never
     * cancel a run that's already in flight. The worker itself passes
     * [ExistingWorkPolicy.REPLACE] when re-enqueueing its own next occurrence after
     * finishing, since at that point superseding "this same unique work" is exactly the
     * intent.
     */
    fun scheduleDailyFetch(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val request = OneTimeWorkRequestBuilder<DailyVerseFetchWorker>()
            .setInitialDelay(delayUntilNext(FETCH_HOUR, FETCH_MINUTE), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(FETCH_WORK_NAME, policy, request)
    }

    /**
     * [policy] defaults to REPLACE: used both when the user changes their reminder time
     * in Settings (must supersede the old schedule) and by the worker re-enqueueing
     * itself for tomorrow (same reasoning as [scheduleDailyFetch]'s REPLACE case). The
     * app-startup safety net (see [com.application.bibleapp.BibleApplication]) passes
     * KEEP instead, for the same reason [scheduleDailyFetch] does: re-arm the job if
     * it's gone missing (an OEM battery manager or Doze-restricted standby bucket can
     * silently drop a pending WorkManager job) without disturbing one already pending.
     */
    fun scheduleNotification(
        context: Context,
        hour: Int,
        minute: Int,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE
    ) {
        val request = OneTimeWorkRequestBuilder<DailyVerseNotificationWorker>()
            .setInitialDelay(delayUntilNext(hour, minute), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(NOTIFICATION_WORK_NAME, policy, request)
    }

    fun cancelNotification(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(NOTIFICATION_WORK_NAME)
    }

    /**
     * Milliseconds from [now] until the next occurrence of [hour]:[minute] in the
     * device's current time zone. Recomputed fresh on every call from the current wall
     * clock rather than ever storing an absolute instant, so this stays correct across
     * DST transitions and time zone changes with no special-casing needed.
     *
     * Cloning [now] (rather than a fresh `Calendar.getInstance()`) for the target
     * instant keeps it in the exact same time zone as [now]. Comparing with
     * `<=`, not "before," matters: if [now] landed exactly on [hour]:[minute] to the
     * millisecond, "before" would be false and this would return a delay of 0 —
     * rescheduling a self-repeating worker to run again immediately instead of
     * tomorrow.
     */
    internal fun delayUntilNext(hour: Int, minute: Int, now: Calendar = Calendar.getInstance()): Long {
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (next.timeInMillis <= now.timeInMillis) next.add(Calendar.DAY_OF_YEAR, 1)
        return next.timeInMillis - now.timeInMillis
    }
}
