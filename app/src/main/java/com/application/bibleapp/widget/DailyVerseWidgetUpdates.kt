package com.application.bibleapp.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.application.bibleapp.worker.DailyVerseScheduler
import java.util.concurrent.TimeUnit

/**
 * When the daily verse widget (#245) is redrawn. No periodic updates and no network: only
 * - just after midnight, so it never shows yesterday's verse (works offline);
 * - when the day's verse is fetched (BibleRepository.refreshDailyVerse);
 * - when the translation or the app language changes, or the phone's language.
 * All of it goes through WorkManager, and only while a widget is placed: removing the last one
 * cancels the midnight job, so nothing keeps running in the background.
 */
object DailyVerseWidgetUpdates {
    private const val MIDNIGHT_WORK_NAME = "daily_verse_widget_midnight"
    private const val REFRESH_WORK_NAME = "daily_verse_widget_refresh"
    internal const val KEY_REARM_MIDNIGHT = "rearm_midnight"

    // A minute after midnight: the new day's curated verse is ready then, and the fetch at 00:05
    // (DailyVerseScheduler) redraws it again with the fetched one.
    private const val MIDNIGHT_HOUR = 0
    private const val MIDNIGHT_MINUTE = 1

    fun hasWidgets(context: Context): Boolean =
        AppWidgetManager.getInstance(context)
            ?.getAppWidgetIds(ComponentName(context, DailyVerseWidgetReceiver::class.java))
            ?.isNotEmpty() == true

    /** Redraws the placed widgets soon; a no-op when there are none. */
    fun refreshNow(context: Context) {
        if (!hasWidgets(context)) return
        val request = OneTimeWorkRequestBuilder<DailyVerseWidgetWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(REFRESH_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Arms the just-after-midnight redraw. KEEP from app start and when a widget is added (don't
     *  disturb one already pending); REPLACE when the worker re-arms itself for the next night. */
    fun scheduleMidnight(context: Context, policy: ExistingWorkPolicy, force: Boolean = false) {
        if (!force && !hasWidgets(context)) return
        val request = OneTimeWorkRequestBuilder<DailyVerseWidgetWorker>()
            .setInitialDelay(DailyVerseScheduler.delayUntilNext(MIDNIGHT_HOUR, MIDNIGHT_MINUTE), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_REARM_MIDNIGHT to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(MIDNIGHT_WORK_NAME, policy, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(MIDNIGHT_WORK_NAME)
            cancelUniqueWork(REFRESH_WORK_NAME)
        }
    }
}

/** Redraws the widgets; the midnight run also arms the next night's, while widgets remain. */
class DailyVerseWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // The last widget was removed since this was scheduled: nothing to draw, and no re-arming.
        if (!DailyVerseWidgetUpdates.hasWidgets(applicationContext)) return Result.success()
        DailyVerseWidget.refreshAll(applicationContext)
        // Last, like DailyVerseNotificationWorker: REPLACE cancels this running instance.
        if (inputData.getBoolean(DailyVerseWidgetUpdates.KEY_REARM_MIDNIGHT, false)) {
            DailyVerseWidgetUpdates.scheduleMidnight(applicationContext, ExistingWorkPolicy.REPLACE)
        }
        return Result.success()
    }
}

/** The widget's entry in the phone's widget picker and its system callbacks. */
class DailyVerseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DailyVerseWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        DailyVerseWidgetUpdates.scheduleMidnight(context, ExistingWorkPolicy.KEEP, force = true)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        DailyVerseWidgetUpdates.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // The phone's language (for "System default"), or on Android 13+ the app's own language
        // changed from the phone's settings rather than in the app.
        if (intent.action == Intent.ACTION_LOCALE_CHANGED || intent.action == ACTION_APPLICATION_LOCALE_CHANGED) {
            DailyVerseWidgetUpdates.refreshNow(context)
        }
    }

    private companion object {
        // Intent.ACTION_APPLICATION_LOCALE_CHANGED, API 33; spelled out so it compiles against older APIs.
        const val ACTION_APPLICATION_LOCALE_CHANGED = "android.intent.action.APPLICATION_LOCALE_CHANGED"
    }
}
