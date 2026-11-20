package com.application.bibleapp.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.application.bibleapp.MainActivity
import com.application.bibleapp.R
import com.application.bibleapp.data.model.DEFAULT_VERSION
import com.application.bibleapp.data.model.DailyVerseLine
import com.application.bibleapp.data.model.DailyVerseUI
import com.application.bibleapp.data.model.MemoryPassage
import com.application.bibleapp.data.remote.HelloAoBibleDataSource
import com.application.bibleapp.data.remote.OurMannaBibleDataSource
import com.application.bibleapp.data.repository.BibleRepository
import com.application.bibleapp.data.repository.dailyVerseDateKey
import com.application.bibleapp.ui.theme.ThemeMode
import com.application.bibleapp.ui.theme.ThemePalette
import com.application.bibleapp.utils.AppLanguage
import com.application.bibleapp.utils.bookNameInVersion
import com.application.bibleapp.utils.formatVerseRefs
import com.application.bibleapp.viewmodel.versionLabelFor

/** MainActivity's extra for a tap on the "Verse to memorize" widget (#364): the passage to open,
 *  as book, chapter, first and last verse. */
const val EXTRA_OPEN_MEMORY_PASSAGE = "com.application.bibleapp.OPEN_MEMORY_PASSAGE"

/** The passage in [EXTRA_OPEN_MEMORY_PASSAGE], or null when the intent has none. */
fun Intent.memoryPassageExtra(): MemoryPassage? =
    getIntArrayExtra(EXTRA_OPEN_MEMORY_PASSAGE)?.takeIf { it.size == 4 }?.let { MemoryPassage(it[0], it[1], it[2], it[3]) }

/**
 * Home screen widget with a passage from the person's own Memorize list (#364), one a day (see
 * [pickMemorizeVerse]), so they see it many times while they learn it. Like the daily verse widget
 * (#245) it only reads the device, works signed out, and has no updates of its own: the daily
 * verse's midnight job and its other redraws ([DailyVerseWidgetUpdates]) redraw this one too, and
 * a change to the list redraws it at once ([MemorizeWidgetUpdates]).
 *
 * The hint toggle switches between the full text and first letters only ([firstLettersHint]); the
 * choice is kept in each widget's own state. Each widget's pick is kept there too, so it stays
 * the same all day and the next day's avoids it. A tap opens the passage in the reader; with
 * practice games (#363) it would open practice instead.
 *
 * Content and state travel like DailyVerseWidget's: see that class for why the newer of the
 * loaded and the stored content is shown.
 */
class MemorizeWidget : GlanceAppWidget() {

    // Exact, so how many lines fit is worked out for the widget's real size.
    override val sizeMode: SizeMode = SizeMode.Exact

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val loaded = loadContent(context, id)
        storeContent(context, id, loaded)
        // Read here, not in the composition: a font size change re-renders the widget anyway.
        val fontScale = context.resources.configuration.fontScale
        provideContent {
            val state = currentState<Preferences>()
            val stored = state.toContent()
            val content = if (stored != null && stored.loadedAt > loaded.loadedAt) stored else loaded
            val hint = state[KEY_HINT] == true
            val colors = widgetColorSource(content.colors, content.palette, content.mode, Build.VERSION.SDK_INT)
            GlanceTheme(colors = colors.glanceColors()) {
                val context = LocalContext.current
                val passage = content.passage
                val open = Intent(context, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                // An empty list (or text that can't be read) opens the app; a passage, the reader on it.
                if (passage != null) {
                    open.putExtra(
                        EXTRA_OPEN_MEMORY_PASSAGE,
                        intArrayOf(passage.bookId, passage.chapter, passage.verseStart, passage.verseEnd)
                    )
                }
                WidgetVerseCard(
                    title = content.title,
                    text = if (hint && passage != null) firstLettersHint(content.text) else content.text,
                    reference = content.reference,
                    fontScale = fontScale,
                    colors = colors,
                    onClick = actionStartActivity(open),
                    titleButton = if (passage == null) null else WidgetTitleButton(
                        icon = if (hint) R.drawable.ic_widget_show_words else R.drawable.ic_widget_hide_words,
                        description = context.getString(if (hint) R.string.widget_memorize_show_words else R.string.widget_memorize_hide_words),
                        onClick = actionRunCallback<ToggleMemorizeHintAction>()
                    )
                )
            }
        }
    }

    companion object {
        private val KEY_TITLE = stringPreferencesKey("title")
        private val KEY_TEXT = stringPreferencesKey("text")
        private val KEY_REFERENCE = stringPreferencesKey("reference")
        private val KEY_LOADED_AT = longPreferencesKey("loaded_at")
        private val KEY_PALETTE = stringPreferencesKey("palette")
        private val KEY_MODE = stringPreferencesKey("mode")
        private val KEY_COLORS = stringPreferencesKey("colors")
        // The passage shown; absent for the empty list.
        private val KEY_BOOK = intPreferencesKey("book")
        private val KEY_CHAPTER = intPreferencesKey("chapter")
        private val KEY_VERSE_START = intPreferencesKey("verse_start")
        private val KEY_VERSE_END = intPreferencesKey("verse_end")
        // Today's pick (MemorizePick): kept across redraws, so it changes only with the day.
        private val KEY_PICK_DAY = stringPreferencesKey("pick_day")
        private val KEY_PICK_ID = longPreferencesKey("pick_id")
        private val KEY_PICK_PREVIOUS_ID = longPreferencesKey("pick_previous_id")
        /** First letters only (#364), per widget; never touched by a redraw. */
        internal val KEY_HINT = booleanPreferencesKey("hint")

        /** Re-picks and redraws every placed widget. */
        suspend fun refreshAll(context: Context) {
            val ids = GlanceAppWidgetManager(context).getGlanceIds(MemorizeWidget::class.java)
            if (ids.isEmpty()) return
            val widget = MemorizeWidget()
            ids.forEach { id ->
                storeContent(context, id, loadContent(context, id))
                widget.update(context, id)
            }
        }

        private suspend fun storeContent(context: Context, id: GlanceId, content: MemorizeContent) {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                prefs.toMutablePreferences().apply {
                    this[KEY_TITLE] = content.title
                    this[KEY_TEXT] = content.text
                    this[KEY_REFERENCE] = content.reference
                    this[KEY_LOADED_AT] = content.loadedAt
                    this[KEY_PALETTE] = content.palette.name
                    this[KEY_MODE] = content.mode.name
                    this[KEY_COLORS] = content.colors.name
                    val passage = content.passage
                    if (passage != null) {
                        this[KEY_BOOK] = passage.bookId
                        this[KEY_CHAPTER] = passage.chapter
                        this[KEY_VERSE_START] = passage.verseStart
                        this[KEY_VERSE_END] = passage.verseEnd
                    } else {
                        remove(KEY_BOOK)
                        remove(KEY_CHAPTER)
                        remove(KEY_VERSE_START)
                        remove(KEY_VERSE_END)
                    }
                    // An empty list keeps the last pick, so the first passage added afterwards
                    // still isn't the one shown just before the list was emptied.
                    content.pick?.let { pick ->
                        this[KEY_PICK_DAY] = pick.day
                        this[KEY_PICK_ID] = pick.localId
                        if (pick.previousLocalId != null) this[KEY_PICK_PREVIOUS_ID] = pick.previousLocalId else remove(KEY_PICK_PREVIOUS_ID)
                    }
                }
            }
        }

        private fun Preferences.toContent(): MemorizeContent? {
            val loadedAt = this[KEY_LOADED_AT] ?: return null
            val book = this[KEY_BOOK]
            val chapter = this[KEY_CHAPTER]
            val start = this[KEY_VERSE_START]
            val end = this[KEY_VERSE_END]
            return MemorizeContent(
                this[KEY_TITLE].orEmpty(), this[KEY_TEXT].orEmpty(), this[KEY_REFERENCE].orEmpty(), loadedAt,
                passage = if (book != null && chapter != null && start != null && end != null) MemoryPassage(book, chapter, start, end) else null,
                pick = null,
                palette = ThemePalette.fromStored(this[KEY_PALETTE]),
                mode = ThemeMode.entries.firstOrNull { it.name == this[KEY_MODE] } ?: ThemeMode.SYSTEM,
                colors = WidgetColors.fromStored(this[KEY_COLORS])
            )
        }

        private fun Preferences.toPick(): MemorizePick? {
            val day = this[KEY_PICK_DAY] ?: return null
            val localId = this[KEY_PICK_ID] ?: return null
            return MemorizePick(day, localId, this[KEY_PICK_PREVIOUS_ID])
        }

        private suspend fun loadContent(context: Context, id: GlanceId): MemorizeContent {
            val loadedAt = System.currentTimeMillis()
            // Built outside an activity, so below Android 13 the app language is applied here.
            val localized = AppLanguage.localizedContext(context)
            val title = localized.getString(R.string.widget_memorize_title)
            val repository = BibleRepository(context, HelloAoBibleDataSource(), OurMannaBibleDataSource())
            // The colors (#333) are read with the passage, so every redraw picks up a theme change.
            val palette = repository.loadThemePalette()
            val mode = repository.loadThemeMode()
            val colors = repository.loadWidgetColors()
            fun message(text: String, pick: MemorizePick?) =
                MemorizeContent(title, text, "", loadedAt, passage = null, pick = pick, palette = palette, mode = mode, colors = colors)

            val verses = runCatching { repository.getAllActiveMemoryVerses() }.getOrDefault(emptyList())
            val stored = getAppWidgetState(context, PreferencesGlanceStateDefinition, id).toPick()
            val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
            val pick = pickMemorizeVerse(verses, dailyVerseDateKey(), widgetId, stored, loadedAt)
                ?: return message(localized.getString(R.string.widget_memorize_empty), null)
            val verse = verses.first { it.localId == pick.localId }

            // In the translation it was added in; bundled KJV if that one isn't on this device
            // (removed, or a reinstall restored the list), as the Library does (#78).
            val versionId = verse.versionId.takeIf {
                it == DEFAULT_VERSION.id || runCatching { repository.isVersionDownloaded(it) }.getOrDefault(false)
            } ?: DEFAULT_VERSION.id
            val lines = runCatching { repository.getChapter(verse.bookId, verse.chapter, versionId) }.getOrDefault(emptyList())
                .mapNotNull { v ->
                    val number = v.verse?.takeIf { it in verse.verseStart..verse.verseEnd } ?: return@mapNotNull null
                    DailyVerseLine(number, v.text.removePrefix(LEGACY_PARAGRAPH_MARKER).trim())
                }
            if (lines.isEmpty()) return message(localized.getString(R.string.widget_memorize_unavailable), pick)

            val bookNames = runCatching { repository.getBookNames(versionId) }.getOrDefault(emptyMap())
            val reference = formatVerseRefs(verse.verses, { bookNameInVersion(versionId, it, mapOf(versionId to bookNames)) }) { ref, _ -> ref }
            val downloaded = runCatching { repository.getDownloadedVersions() }.getOrDefault(emptyList())
            val label = versionLabelFor(versionId, emptyList(), downloaded.associateBy { it.id })
            return MemorizeContent(
                title, DailyVerseUI(reference, lines).text, "$reference · $label", loadedAt,
                passage = MemoryPassage(verse.bookId, verse.chapter, verse.verseStart, verse.verseEnd),
                pick = pick, palette = palette, mode = mode, colors = colors
            )
        }

        // Older downloads start a paragraph's first verse with this; the reader turns it into a break.
        private const val LEGACY_PARAGRAPH_MARKER = "¶ "
    }
}

/** What one widget shows. [passage] is null for a message (empty list) instead of a passage;
 *  [pick] is only set when freshly loaded, since it's stored apart from the content. */
private data class MemorizeContent(
    val title: String,
    val text: String,
    val reference: String,
    val loadedAt: Long,
    val passage: MemoryPassage?,
    val pick: MemorizePick?,
    val palette: ThemePalette,
    val mode: ThemeMode,
    val colors: WidgetColors
)

/** The widget's hint toggle (#364): flips this widget's mode and redraws it. Nothing is reloaded,
 *  so the passage stays the same. */
class ToggleMemorizeHintAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            prefs.toMutablePreferences().apply { this[MemorizeWidget.KEY_HINT] = this[MemorizeWidget.KEY_HINT] != true }
        }
        MemorizeWidget().update(context, glanceId)
    }
}

/** Redraws the memorize widgets when the Memorize list changes: added, removed, restored, synced
 *  or signed out (#364), so a removed passage is replaced at once. A no-op without a widget. */
object MemorizeWidgetUpdates {
    private const val REFRESH_WORK_NAME = "memorize_widget_refresh"

    fun hasWidgets(context: Context): Boolean =
        AppWidgetManager.getInstance(context)
            ?.getAppWidgetIds(ComponentName(context, MemorizeWidgetReceiver::class.java))
            ?.isNotEmpty() == true

    fun refreshNow(context: Context) {
        if (!hasWidgets(context)) return
        val request = OneTimeWorkRequestBuilder<MemorizeWidgetWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(REFRESH_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}

class MemorizeWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        MemorizeWidget.refreshAll(applicationContext)
        return Result.success()
    }
}

/** The widget's entry in the phone's widget picker and its system callbacks. It shares the daily
 *  verse widget's midnight job, which redraws both. */
class MemorizeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MemorizeWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        DailyVerseWidgetUpdates.scheduleMidnight(context, ExistingWorkPolicy.KEEP, force = true)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // The daily verse widget or wallpaper (#257) may still need the midnight update.
        if (!DailyVerseWidgetUpdates.isNeeded(context)) DailyVerseWidgetUpdates.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // The title follows the phone's language, or on Android 13+ the app's own set there.
        if (intent.action == Intent.ACTION_LOCALE_CHANGED || intent.action == ACTION_APPLICATION_LOCALE_CHANGED) {
            MemorizeWidgetUpdates.refreshNow(context)
        }
    }

    private companion object {
        // Intent.ACTION_APPLICATION_LOCALE_CHANGED, API 33; spelled out so it compiles against older APIs.
        const val ACTION_APPLICATION_LOCALE_CHANGED = "android.intent.action.APPLICATION_LOCALE_CHANGED"
    }
}
