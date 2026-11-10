package com.application.bibleapp.verseimage

import android.content.Context
import android.util.Log
import com.application.bibleapp.data.remote.HelloAoBibleDataSource
import com.application.bibleapp.data.remote.OurMannaBibleDataSource
import com.application.bibleapp.data.repository.BibleRepository
import com.application.bibleapp.data.repository.resolveDailyVerseText
import com.application.bibleapp.data.repository.todaysDailyVerseOffline
import com.application.bibleapp.ui.theme.VerseImageStyle
import com.application.bibleapp.viewmodel.translationCreditFor
import com.application.bibleapp.viewmodel.versionLabelFor
import com.application.bibleapp.widget.DailyVerseWidgetUpdates
import kotlinx.coroutines.CancellationException

/** The daily verse wallpaper's settings (#257): off unless turned on in Settings. */
data class DailyWallpaperSettings(
    val enabled: Boolean = false,
    val target: WallpaperTarget = WallpaperTarget.BOTH,
    val style: VerseImageStyle = VerseImageStyle.PARCHMENT
)

/**
 * Sets today's verse as the wallpaper, once a day (#257). It rides on the daily verse widget's
 * updates ([DailyVerseWidgetUpdates]): just after midnight, when the day's verse is fetched, and
 * when the translation or language changes. So it reads only what's on the phone, makes no
 * network calls, and adds no background work of its own.
 *
 * The settings live in their own preferences file, which backups and phone transfers don't copy
 * (res/xml/data_extraction_rules.xml): replacing the wallpaper every day is a choice made on one
 * phone, and a restored backup must never start doing it on a new one without asking.
 */
object DailyWallpaper {
    private const val PREFS_NAME = "daily_wallpaper"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TARGET = "target"
    private const val KEY_STYLE = "style"

    /** What was last set, so the same verse isn't set again on every widget refresh. */
    private const val KEY_APPLIED = "applied"
    private const val TAG = "DailyWallpaper"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun load(context: Context, fallbackStyle: VerseImageStyle): DailyWallpaperSettings {
        val prefs = prefs(context)
        return DailyWallpaperSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            target = WallpaperTarget.entries.firstOrNull { it.name == prefs.getString(KEY_TARGET, null) }
                ?: WallpaperTarget.BOTH,
            style = VerseImageStyle.entries.firstOrNull { it.name == prefs.getString(KEY_STYLE, null) }
                ?: fallbackStyle
        )
    }

    /**
     * Saves [settings] and starts or stops the daily updates. Turning it on (or changing where or
     * how) sets today's wallpaper straight away. Turning it off leaves the current wallpaper, and
     * stops the midnight job unless a widget still needs it.
     */
    fun save(context: Context, settings: DailyWallpaperSettings) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_TARGET, settings.target.name)
            .putString(KEY_STYLE, settings.style.name)
            .remove(KEY_APPLIED)
            .apply()
        if (settings.enabled) {
            DailyVerseWidgetUpdates.scheduleMidnight(context, androidx.work.ExistingWorkPolicy.KEEP, force = true)
            DailyVerseWidgetUpdates.refreshNow(context)
        } else if (!DailyVerseWidgetUpdates.hasWidgets(context)) {
            DailyVerseWidgetUpdates.cancel(context)
        }
    }

    /**
     * Sets today's verse as the wallpaper if it's turned on and not set already. Never throws: a
     * failure is logged and tried again on the next update. If the phone no longer allows changing
     * the wallpaper (a new work policy, say), it turns itself off.
     */
    suspend fun update(context: Context) {
        val settings = load(context, VerseImageStyle.PARCHMENT)
        if (!settings.enabled) return
        if (!VerseImageSaving.canSetWallpaper(context)) {
            prefs(context).edit().putBoolean(KEY_ENABLED, false).apply()
            return
        }
        try {
            val repository = BibleRepository(context, HelloAoBibleDataSource(), OurMannaBibleDataSource())
            val resolved = repository.resolveDailyVerseText(
                repository.todaysDailyVerseOffline(), repository.loadSelectedVersion()
            ) ?: return
            val downloaded = repository.getDownloadedVersions().associateBy { it.id }
            val content = VerseImageContent(
                lines = resolved.verse.verses.map { VerseImageLine(it.number, it.text) },
                reference = resolved.verse.reference,
                versionLabel = versionLabelFor(resolved.versionId, emptyList(), downloaded),
                notice = translationCreditFor(resolved.versionId, emptyList(), downloaded).license?.notice
            )
            val (width, height) = VerseImageSaving.screenSize(context)
            val key = appliedKey(content, settings, width, height)
            if (prefs(context).getString(KEY_APPLIED, null) == key) return
            val options = VerseImageOptions(style = settings.style)
            val bitmap = VerseImageRenderer.render(content, options, width, height, forWallpaper = true)
            VerseImageSaving.setWallpaper(context, bitmap, settings.target)
            prefs(context).edit().putString(KEY_APPLIED, key).apply()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't set the daily verse wallpaper", e)
        }
    }

    /** Everything the wallpaper is drawn from; a change in any of it draws a new one. */
    internal fun appliedKey(content: VerseImageContent, settings: DailyWallpaperSettings, width: Int, height: Int): String =
        listOf(
            content.reference, content.versionLabel, content.notice.orEmpty(),
            content.lines.joinToString("\u0001") { "${it.number} ${it.text}" }.hashCode().toString(),
            settings.target.name, settings.style.name, "${width}x$height"
        ).joinToString("|")
}
