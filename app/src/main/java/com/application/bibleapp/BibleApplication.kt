package com.application.bibleapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.application.bibleapp.data.remote.HttpClientProvider
import com.application.bibleapp.worker.DailyVerseScheduler
import com.application.bibleapp.worker.SyncScheduler
import android.content.Context
import com.application.bibleapp.utils.AppLanguage

/**
 * Runs the app-startup jobs the daily-verse feature and highlight/note/reading-progress sync
 * (Phase E) need:
 * - Create the notification channel (a no-op pre-API 26, required from O onward before
 *   any notification on [DailyVerseScheduler.NOTIFICATION_CHANNEL_ID] can show).
 * - [ensureDailyVerseJobsScheduled] re-arms the fetch job and, if enabled, the
 *   reminder notification job — see its doc for why this needs to run here (every
 *   normal process start) *and* from [com.application.bibleapp.receiver.BootCompletedReceiver]
 *   (so it also happens after a reboot the user hasn't opened the app since).
 * - [SyncScheduler.schedulePeriodicSync]/[SyncScheduler.triggerImmediateSync] re-arm and kick off
 *   [com.application.bibleapp.worker.SyncWorker] — a no-op while signed out.
 */
class BibleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Must happen before anything touches HttpClientProvider.client — it reads this at
        // client-creation time to wire up encrypted token storage for the backend's auth.
        HttpClientProvider.init(this)
        createDailyVerseNotificationChannel(AppLanguage.localizedContext(this))
        ensureDailyVerseJobsScheduled(this)
        SyncScheduler.schedulePeriodicSync(this)
        // A no-op if signed out (SyncWorker checks first thing) — otherwise syncs promptly on
        // launch instead of waiting up to 15 minutes for the periodic job's next run.
        SyncScheduler.triggerImmediateSync(this)
    }
}

/** Creates the channel, or renames it (its name shows in the system's notification settings) —
 *  [context] decides the language. Safe to call repeatedly; the user's own channel settings stay. */
fun createDailyVerseNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
        DailyVerseScheduler.NOTIFICATION_CHANNEL_ID,
        context.getString(R.string.notification_channel_daily_verse),
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = context.getString(R.string.notification_channel_daily_verse_description)
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}
