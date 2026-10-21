package com.application.bibleapp.worker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.application.bibleapp.MainActivity
import com.application.bibleapp.R
import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.data.model.DailyVerseRef
import com.application.bibleapp.data.model.DailyVerseUI
import com.application.bibleapp.data.model.VerseOfTheDay
import com.application.bibleapp.data.model.resolveDailyVerseUI
import com.application.bibleapp.data.remote.HelloAoBibleDataSource
import com.application.bibleapp.data.remote.OurMannaBibleDataSource
import com.application.bibleapp.data.repository.BibleRepository
import com.application.bibleapp.utils.AppLanguage

/**
 * Runs at the user's chosen local time (see [DailyVerseScheduler]) and posts a
 * notification with the daily verse, read entirely from local storage — the cached
 * reference from [com.application.bibleapp.data.local.BibleDatabaseManager] (kept
 * fresh by [DailyVerseFetchWorker]) and the verse text from whichever translation is
 * currently selected. No network call happens here.
 */
class DailyVerseNotificationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = BibleRepository(
            context = applicationContext,
            remote = HelloAoBibleDataSource(),
            daily = OurMannaBibleDataSource()
        )

        if (repository.isNotificationEnabled()) {
            // Post first, reschedule last: scheduleNotification() re-enqueues tomorrow's
            // occurrence under this same unique work name with ExistingWorkPolicy.REPLACE,
            // which cancels any existing work under that name — including *this
            // currently-running instance*. Calling it before postNotification() raced
            // that self-cancellation against actually posting the notification: on a
            // warm/foreground run postNotification() usually won, but on a cold
            // background start (DB open, class loading, etc.) the cancellation could
            // land first and the notification silently never posted. Doing it last
            // avoids the race entirely — there's nothing left to interrupt afterward.
            postNotification(repository)
            // Read fresh each run (not the value at schedule time) so a time change in
            // Settings takes effect starting with the very next notification.
            val time = repository.loadNotificationTime()
            DailyVerseScheduler.scheduleNotification(applicationContext, time.hour, time.minute)
        }
        return Result.success()
    }

    private suspend fun postNotification(repository: BibleRepository) {
        if (ActivityCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS revoked after the reminder was enabled — skipping")
            return
        }

        val versionId = repository.loadSelectedVersion()
        val ref = runCatching { repository.getDailyVerse() }.getOrElse { VerseOfTheDay.forToday() }

        // The cached/fallback ref is resolved against whatever the *chosen* translation
        // has locally, which can legitimately be missing it (not downloaded, deleted
        // since, or a partial-canon translation that never covers that book) — in which
        // case this would otherwise post nothing at all, silently, forever. KJV is
        // bundled and always has the full canon, so it's a fallback that's guaranteed to
        // resolve for any ref this app itself produces.
        val verse = resolveVerse(repository, ref, versionId)
            ?: resolveVerse(repository, VerseOfTheDay.forToday(), "kjv")
        if (verse == null) {
            Log.w(TAG, "Could not resolve daily verse (ref=$ref, version=$versionId) even with KJV fallback")
            return
        }

        // FLAG_ACTIVITY_CLEAR_TASK, not just NEW_TASK: MainActivity has no launchMode
        // override, so without CLEAR_TASK a plain NEW_TASK intent would just bring the
        // app's existing task back to whatever screen the user last left it on. Tapping
        // this notification should always land on Home, so the old task (and whatever
        // Compose nav state it held) is discarded and MainActivity starts fresh, which
        // means the NavHost begins again at its startDestination (Home).
        val openAppIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, DailyVerseScheduler.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_daily_verse)
            .setColor(BRAND_COLOR)
            // Workers run without an activity, so below Android 13 the app language has to be applied here.
            .setContentTitle(AppLanguage.localizedContext(applicationContext).getString(R.string.daily_verse_notification_title))
            .setContentText(verse.reference)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(verse.text)
                    .setBigContentTitle(verse.reference)
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // channel importance governs API 26+; this covers 24-25
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
    }

    private suspend fun resolveVerse(repository: BibleRepository, ref: DailyVerseRef, versionId: String): DailyVerseUI? {
        val bookName = repository.getBookNames(versionId)[ref.bookId]
            ?: BibleBooks.getBookById(ref.bookId)?.name
            ?: return null
        val chapterVerses = repository.getChapter(ref.bookId, ref.chapter, versionId)
        return resolveDailyVerseUI(bookName, ref, chapterVerses)
    }

    private companion object {
        const val TAG = "DailyVerseNotifWorker"
        const val NOTIFICATION_ID = 1001

        // Matches ui/theme/Color.kt's LightPrimary, so the notification reads as part of
        // the same app instead of using the OS's generic default accent.
        const val BRAND_COLOR = 0xFFA6743A.toInt()
    }
}
