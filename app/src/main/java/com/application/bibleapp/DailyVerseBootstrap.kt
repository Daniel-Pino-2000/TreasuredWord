package com.application.bibleapp

import android.content.Context
import androidx.work.ExistingWorkPolicy
import com.application.bibleapp.data.remote.HelloAoBibleDataSource
import com.application.bibleapp.data.remote.OurMannaBibleDataSource
import com.application.bibleapp.data.repository.BibleRepository
import com.application.bibleapp.worker.DailyVerseScheduler
import com.application.bibleapp.widget.DailyVerseWidgetUpdates

/**
 * Ensures both daily-verse background jobs (fetch + reminder notification) are
 * scheduled. Called from [BibleApplication.onCreate] on every normal process start,
 * and from [com.application.bibleapp.receiver.BootCompletedReceiver] after a device
 * reboot or app update — those events can leave WorkManager's persisted jobs dropped
 * (some OEM battery managers clear scheduled jobs outright, especially under
 * low-battery power saving), and without the boot receiver nothing would re-arm them
 * until the user happened to open the app again.
 *
 * Both call sites end up using [ExistingWorkPolicy.KEEP] for the notification job
 * (the default inside [DailyVerseScheduler.scheduleDailyFetch], and explicitly here
 * for the notification), so this is a no-op whenever a job is already pending — it
 * only fills in a schedule that's gone missing.
 */
fun ensureDailyVerseJobsScheduled(context: Context) {
    DailyVerseScheduler.scheduleDailyFetch(context)
    // The widget's midnight redraw (#245), if a widget is placed and the job went missing.
    DailyVerseWidgetUpdates.scheduleMidnight(context, ExistingWorkPolicy.KEEP)

    val repository = BibleRepository(
        context = context,
        remote = HelloAoBibleDataSource(),
        daily = OurMannaBibleDataSource()
    )
    if (repository.isNotificationEnabled()) {
        val time = repository.loadNotificationTime()
        DailyVerseScheduler.scheduleNotification(context, time.hour, time.minute, ExistingWorkPolicy.KEEP)
    }
}
