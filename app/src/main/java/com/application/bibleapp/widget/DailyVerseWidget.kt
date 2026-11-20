package com.application.bibleapp.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.application.bibleapp.MainActivity
import com.application.bibleapp.R
import com.application.bibleapp.data.remote.HelloAoBibleDataSource
import com.application.bibleapp.data.remote.OurMannaBibleDataSource
import com.application.bibleapp.data.repository.BibleRepository
import com.application.bibleapp.data.repository.resolveDailyVerseText
import com.application.bibleapp.data.repository.todaysDailyVerseOffline
import com.application.bibleapp.ui.theme.ThemeMode
import com.application.bibleapp.ui.theme.ThemePalette
import com.application.bibleapp.utils.AppLanguage
import com.application.bibleapp.verseimage.dailyVerseImageIntent
import com.application.bibleapp.viewmodel.versionLabelFor

/**
 * Home screen widget with today's daily verse (#245). It only reads the device: today's cached
 * verse or the curated fallback, in the chosen translation or bundled KJV — the same lookup as
 * the daily verse notification. It never calls the network and has no periodic updates; it's
 * refreshed by [DailyVerseWidgetUpdates] when the day changes, the verse is fetched, or the
 * translation or app language changes.
 *
 * A refresh while Glance's session is still alive doesn't run [provideGlance] again, it only
 * reloads the widget's state and recomposes, so [refreshAll] puts the new verse in that state.
 * The state a session starts with is read before [provideGlance] runs, though, so the content
 * shows whichever is newer: the verse loaded at the start, or one stored since. The colors
 * (#333) travel with the verse the same way, so a theme change in the app redraws a live widget.
 */
class DailyVerseWidget : GlanceAppWidget() {

    // Exact, so how many lines fit is worked out for the widget's real size.
    override val sizeMode: SizeMode = SizeMode.Exact

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val loaded = loadVerse(context)
        storeVerse(context, id, loaded)
        // Read here, not in the composition: a font size change re-renders the widget anyway.
        val fontScale = context.resources.configuration.fontScale
        provideContent {
            val stored = currentState<Preferences>().toWidgetVerse()
            val verse = if (stored != null && stored.loadedAt > loaded.loadedAt) stored else loaded
            val colors = widgetColorSource(verse.colors, verse.palette, verse.mode, Build.VERSION.SDK_INT)
            GlanceTheme(colors = colors.glanceColors()) {
                val context = LocalContext.current
                // CLEAR_TASK like the notification: always lands on Home, where the daily verse is.
                val openHome = Intent(context, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                WidgetVerseCard(
                    title = verse.title, text = verse.text, reference = verse.reference,
                    fontScale = fontScale, colors = colors,
                    onClick = actionStartActivity(openHome),
                    titleButton = WidgetTitleButton(
                        R.drawable.ic_share_image, context.getString(R.string.share_as_image),
                        actionStartActivity(dailyVerseImageIntent(context))
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

        /** Reloads today's verse into every placed widget and redraws them. */
        suspend fun refreshAll(context: Context) {
            val ids = GlanceAppWidgetManager(context).getGlanceIds(DailyVerseWidget::class.java)
            if (ids.isEmpty()) return
            val verse = loadVerse(context)
            val widget = DailyVerseWidget()
            ids.forEach { id ->
                storeVerse(context, id, verse)
                widget.update(context, id)
            }
        }

        private suspend fun storeVerse(context: Context, id: GlanceId, verse: WidgetVerse) {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                prefs.toMutablePreferences().apply {
                    this[KEY_TITLE] = verse.title
                    this[KEY_TEXT] = verse.text
                    this[KEY_REFERENCE] = verse.reference
                    this[KEY_LOADED_AT] = verse.loadedAt
                    this[KEY_PALETTE] = verse.palette.name
                    this[KEY_MODE] = verse.mode.name
                    this[KEY_COLORS] = verse.colors.name
                }
            }
        }

        private fun Preferences.toWidgetVerse(): WidgetVerse? {
            val loadedAt = this[KEY_LOADED_AT] ?: return null
            return WidgetVerse(
                this[KEY_TITLE].orEmpty(), this[KEY_TEXT].orEmpty(), this[KEY_REFERENCE].orEmpty(), loadedAt,
                palette = ThemePalette.fromStored(this[KEY_PALETTE]),
                mode = ThemeMode.entries.firstOrNull { it.name == this[KEY_MODE] } ?: ThemeMode.SYSTEM,
                colors = WidgetColors.fromStored(this[KEY_COLORS])
            )
        }

        private suspend fun loadVerse(context: Context): WidgetVerse {
            val loadedAt = System.currentTimeMillis()
            // Built outside an activity, so below Android 13 the app language is applied here.
            val localized = AppLanguage.localizedContext(context)
            val title = localized.getString(R.string.verse_of_the_day)
            val repository = BibleRepository(context, HelloAoBibleDataSource(), OurMannaBibleDataSource())
            // The colors (#333) are read with the verse, so every redraw picks up a theme change.
            val palette = repository.loadThemePalette()
            val mode = repository.loadThemeMode()
            val colors = repository.loadWidgetColors()
            val resolved = runCatching {
                repository.resolveDailyVerseText(repository.todaysDailyVerseOffline(), repository.loadSelectedVersion())
            }.getOrNull()
                ?: return WidgetVerse(
                    title, localized.getString(R.string.widget_daily_verse_unavailable), "", loadedAt, palette, mode, colors
                )
            val downloaded = runCatching { repository.getDownloadedVersions() }.getOrDefault(emptyList())
            val label = versionLabelFor(resolved.versionId, emptyList(), downloaded.associateBy { it.id })
            return WidgetVerse(title, resolved.verse.text, "${resolved.verse.reference} · $label", loadedAt, palette, mode, colors)
        }
    }
}

private data class WidgetVerse(
    val title: String,
    val text: String,
    val reference: String,
    val loadedAt: Long,
    val palette: ThemePalette,
    val mode: ThemeMode,
    val colors: WidgetColors
)
