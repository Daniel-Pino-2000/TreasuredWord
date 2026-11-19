package com.application.bibleapp.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import com.application.bibleapp.verseimage.dailyVerseImageIntent
import androidx.glance.layout.size
import androidx.glance.layout.Row
import androidx.glance.layout.Box
import androidx.glance.layout.Alignment
import androidx.glance.ImageProvider
import androidx.glance.Image
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProviders
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
import com.application.bibleapp.ui.theme.ThemeMode
import com.application.bibleapp.ui.theme.ThemePalette
import com.application.bibleapp.utils.AppLanguage
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
                WidgetContent(
                    title = verse.title, text = verse.text, reference = verse.reference,
                    fontScale = fontScale, colors = colors
                )
            }
        }
    }

    @Composable
    private fun WidgetContent(title: String, text: String, reference: String, fontScale: Float, colors: WidgetColorSource) {
        val size = LocalSize.current
        val context = LocalContext.current
        val autoSize = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        // Measured as the TextView draws it (#272): Android 8+'s XML layout has no font padding.
        val layout = fitWidgetVerse(text, size, fontScale, autoSize, StaticLayoutVerseMeasurer(context, includePad = !autoSize))
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
                // Less at the top with the title row, whose share button (#256) is taller than the
                // title: the title still sits where it did, and the verse loses less room.
                .padding(
                    start = PADDING_HORIZONTAL,
                    end = PADDING_HORIZONTAL,
                    top = if (layout.showTitle) PADDING_TOP_WITH_TITLE else PADDING_VERTICAL,
                    bottom = PADDING_VERTICAL
                )
                .clickable(actionStartActivity(openHome))
        ) {
            if (layout.showTitle) {
                // The title row carries "Share image" (#256), so it's hidden with the title at the
                // smallest sizes, where there's no room.
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Box(
                        modifier = GlanceModifier
                            .size(TITLE_ROW_HEIGHT_DP.dp)
                            .cornerRadius((TITLE_ROW_HEIGHT_DP / 2).dp)
                            .clickable(actionStartActivity(dailyVerseImageIntent(context))),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_share_image),
                            contentDescription = context.getString(R.string.share_as_image),
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                            modifier = GlanceModifier.size(20.dp)
                        )
                    }
                }
            }
            if (layout.autoSize && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8+: an autosizing TextView, so the launcher sizes the real text to the
                // real room (#272): a short verse grows to fill a big widget, a long one shrinks
                // before it's cut. maxLines lets it end with "…" if the room is less than measured.
                val views = RemoteViews(context.packageName, R.layout.widget_daily_verse_text).apply {
                    setTextViewText(R.id.widget_verse_text, layout.text)
                    setInt(R.id.widget_verse_text, "setMaxLines", layout.maxLines)
                    // The same color as the title and reference, which Glance draws. Always set,
                    // never left to the layout: the launcher reuses the view, so a color set for
                    // the previous theme would stay (#333). With the wallpaper's colors (Android
                    // 12+), the layout's resource, the same system color Glance uses. With the
                    // app's theme following the phone on Android 12+, both its light and dark
                    // colors, so it switches with the phone as Glance's text does. Otherwise one
                    // fixed color: below Android 12 Glance fixes its own (light or dark) too.
                    val sdk = Build.VERSION.SDK_INT
                    if (colors is WidgetColorSource.Wallpaper && sdk >= Build.VERSION_CODES.S) {
                        setColorStateList(R.id.widget_verse_text, "setTextColor", R.color.widget_verse_text)
                    } else if (colors is WidgetColorSource.Palette && colors.isDark == null && sdk >= Build.VERSION_CODES.S) {
                        setColorInt(
                            R.id.widget_verse_text, "setTextColor",
                            colors.palette.colorScheme(isDark = false).onPrimaryContainer.toArgb(),
                            colors.palette.colorScheme(isDark = true).onPrimaryContainer.toArgb()
                        )
                    } else {
                        setTextColor(R.id.widget_verse_text, GlanceTheme.colors.onPrimaryContainer.getColor(context).toArgb())
                    }
                }
                AndroidRemoteViews(remoteViews = views, modifier = GlanceModifier.fillMaxWidth().defaultWeight())
            } else {
                // Android 7 has no autosizing in widgets: the largest size that holds the verse.
                Text(
                    text = layout.text,
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimaryContainer,
                        fontSize = layout.fontSizeSp.sp,
                        fontFamily = FontFamily.Serif
                    ),
                    maxLines = layout.maxLines,
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                )
            }
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
        private val KEY_PALETTE = stringPreferencesKey("palette")
        private val KEY_MODE = stringPreferencesKey("mode")
        private val KEY_COLORS = stringPreferencesKey("colors")

        private val PADDING_HORIZONTAL = WIDGET_PADDING_HORIZONTAL_DP.dp
        private val PADDING_VERTICAL = 12.dp
        private val PADDING_TOP_WITH_TITLE = 4.dp

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

        /** The wallpaper's colors (Material You), or the app's color theme (#333): fixed to light
         *  or dark when the app is, both otherwise so Glance follows the phone's mode. */
        private fun WidgetColorSource.glanceColors(): ColorProviders = when (this) {
            WidgetColorSource.Wallpaper -> DynamicThemeColorProviders
            is WidgetColorSource.Palette -> if (isDark == null) {
                ColorProviders(light = palette.colorScheme(isDark = false), dark = palette.colorScheme(isDark = true))
            } else {
                ColorProviders(palette.colorScheme(isDark))
            }
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
