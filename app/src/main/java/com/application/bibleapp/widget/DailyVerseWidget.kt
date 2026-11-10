package com.application.bibleapp.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.DynamicThemeColorProviders
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.application.bibleapp.MainActivity
import com.application.bibleapp.R
import com.application.bibleapp.data.remote.HelloAoBibleDataSource
import com.application.bibleapp.data.remote.OurMannaBibleDataSource
import com.application.bibleapp.data.repository.BibleRepository
import com.application.bibleapp.data.repository.resolveDailyVerseText
import com.application.bibleapp.data.repository.todaysDailyVerseOffline
import com.application.bibleapp.ui.theme.ThemePalette
import com.application.bibleapp.utils.AppLanguage
import com.application.bibleapp.viewmodel.versionLabelFor
import kotlin.math.floor

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
 * shows whichever is newer: the verse loaded at the start, or one stored since.
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
            GlanceTheme(colors = widgetColors()) {
                val stored = currentState<Preferences>().toWidgetVerse()
                val verse = if (stored != null && stored.loadedAt > loaded.loadedAt) stored else loaded
                WidgetContent(title = verse.title, text = verse.text, reference = verse.reference, fontScale = fontScale)
            }
        }
    }

    @Composable
    private fun WidgetContent(title: String, text: String, reference: String, fontScale: Float) {
        val size = LocalSize.current
        val context = LocalContext.current
        val layout = widgetTextLayout(size, fontScale)
        // CLEAR_TASK like the notification: always lands on Home, where the daily verse is.
        val openHome = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .then(
                    // Android 12+'s standard widget corners; older launchers draw their own frame.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        GlanceModifier.cornerRadius(android.R.dimen.system_app_widget_background_radius)
                    } else {
                        GlanceModifier
                    }
                )
                .background(GlanceTheme.colors.primaryContainer)
                .padding(horizontal = PADDING_HORIZONTAL, vertical = PADDING_VERTICAL)
                .clickable(actionStartActivity(openHome))
        ) {
            if (layout.showTitle) {
                Text(
                    text = title,
                    style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1
                )
                Spacer(GlanceModifier.height(4.dp))
            }
            Text(
                text = fitVerse(text, layout.maxChars),
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                    fontSize = layout.verseFontSize.sp,
                    fontFamily = FontFamily.Serif
                ),
                maxLines = layout.maxLines,
                modifier = GlanceModifier.fillMaxWidth().defaultWeight()
            )
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = reference,
                style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1
            )
        }
    }

    companion object {
        private val KEY_TITLE = stringPreferencesKey("title")
        private val KEY_TEXT = stringPreferencesKey("text")
        private val KEY_REFERENCE = stringPreferencesKey("reference")
        private val KEY_LOADED_AT = longPreferencesKey("loaded_at")

        private val PADDING_HORIZONTAL = 16.dp
        private val PADDING_VERTICAL = 12.dp

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
                }
            }
        }

        private fun Preferences.toWidgetVerse(): WidgetVerse? {
            val loadedAt = this[KEY_LOADED_AT] ?: return null
            return WidgetVerse(this[KEY_TITLE].orEmpty(), this[KEY_TEXT].orEmpty(), this[KEY_REFERENCE].orEmpty(), loadedAt)
        }

        private suspend fun loadVerse(context: Context): WidgetVerse {
            val loadedAt = System.currentTimeMillis()
            // Built outside an activity, so below Android 13 the app language is applied here.
            val localized = AppLanguage.localizedContext(context)
            val title = localized.getString(R.string.verse_of_the_day)
            val repository = BibleRepository(context, HelloAoBibleDataSource(), OurMannaBibleDataSource())
            val resolved = runCatching {
                repository.resolveDailyVerseText(repository.todaysDailyVerseOffline(), repository.loadSelectedVersion())
            }.getOrNull()
                ?: return WidgetVerse(title, localized.getString(R.string.widget_daily_verse_unavailable), "", loadedAt)
            val downloaded = runCatching { repository.getDownloadedVersions() }.getOrDefault(emptyList())
            val label = versionLabelFor(resolved.versionId, emptyList(), downloaded.associateBy { it.id })
            return WidgetVerse(title, resolved.verse.text, "${resolved.verse.reference} · $label", loadedAt)
        }

        /** Android 12+ follows the wallpaper's colors (Material You); below that the app's own
         *  default theme. Light or dark follows the phone, since the home screen is the phone's. */
        private fun widgetColors() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicThemeColorProviders
        } else {
            ColorProviders(
                light = ThemePalette.PARCHMENT.colorScheme(isDark = false),
                dark = ThemePalette.PARCHMENT.colorScheme(isDark = true)
            )
        }
    }
}

private data class WidgetVerse(val title: String, val text: String, val reference: String, val loadedAt: Long)

/** How the verse fits a widget: whether the title shows, the verse's font size, how many lines
 *  fit, and roughly how many characters those hold. Widgets can't measure text, so this
 *  estimates from the size. */
internal data class WidgetTextLayout(val showTitle: Boolean, val verseFontSize: Int, val maxLines: Int, val maxChars: Int)

internal fun widgetTextLayout(size: DpSize, fontScale: Float): WidgetTextLayout {
    val showTitle = size.height >= TITLE_MIN_HEIGHT
    val fontSize = if (size.height >= 150.dp && size.width >= 200.dp) 16 else 14
    val lineHeight = fontSize * LINE_HEIGHT_FACTOR * fontScale
    // Vertical padding, the reference line and its gap, and the title and its gap when shown.
    val reserved = 24f + 4f + 18f * fontScale + if (showTitle) 4f + 16f * fontScale else 0f
    val maxLines = floor((size.height.value - reserved) / lineHeight).toInt().coerceAtLeast(1)
    val charsPerLine = ((size.width.value - 32f) / (fontSize * fontScale * AVERAGE_CHAR_WIDTH)).toInt().coerceAtLeast(8)
    return WidgetTextLayout(showTitle, fontSize, maxLines, maxLines * charsPerLine)
}

/** [text] cut at a word to about [maxChars], ending in "…" when it was cut. */
internal fun fitVerse(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val cut = text.take((maxChars - 1).coerceAtLeast(1))
    val atWord = cut.substringBeforeLast(' ', cut).ifBlank { cut }
    return atWord.trimEnd(' ', ',', ';', ':', '.', '—', '-') + "…"
}

private val TITLE_MIN_HEIGHT = 100.dp
private const val LINE_HEIGHT_FACTOR = 1.25f
private const val AVERAGE_CHAR_WIDTH = 0.5f
