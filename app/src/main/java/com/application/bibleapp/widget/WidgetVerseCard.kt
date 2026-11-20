package com.application.bibleapp.widget

import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProviders
import androidx.glance.color.DynamicThemeColorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.application.bibleapp.R

/** The small button at the end of a widget's title row: the daily verse's "Share image" (#256),
 *  the memorize widget's hint toggle (#364). */
internal class WidgetTitleButton(@DrawableRes val icon: Int, val description: String, val onClick: Action)

/**
 * A verse widget's card: title row, the verse fitted to the widget's size (#272), and its
 * reference, in the colors of [colors] (#333). Shared by the daily verse widget (#245) and the
 * "Verse to memorize" widget (#364); [fitWidgetVerse] measures this exact layout, so change both
 * together.
 */
@Composable
internal fun WidgetVerseCard(
    title: String,
    text: String,
    reference: String,
    fontScale: Float,
    colors: WidgetColorSource,
    onClick: Action,
    titleButton: WidgetTitleButton?
) {
    val size = LocalSize.current
    val context = LocalContext.current
    val autoSize = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    // Measured as the TextView draws it (#272): Android 8+'s XML layout has no font padding.
    val layout = fitWidgetVerse(text, size, fontScale, autoSize, StaticLayoutVerseMeasurer(context, includePad = !autoSize))
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
            // Less at the top with the title row, whose button (#256) is taller than the title:
            // the title still sits where it did, and the verse loses less room.
            .padding(
                start = PADDING_HORIZONTAL,
                end = PADDING_HORIZONTAL,
                top = if (layout.showTitle) PADDING_TOP_WITH_TITLE else PADDING_VERTICAL,
                bottom = PADDING_VERTICAL
            )
            .clickable(onClick)
    ) {
        if (layout.showTitle) {
            // The title row carries the button, so it's hidden with the title at the smallest
            // sizes, where there's no room.
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight()
                )
                if (titleButton != null) {
                    Box(
                        modifier = GlanceModifier
                            .size(TITLE_ROW_HEIGHT_DP.dp)
                            .cornerRadius((TITLE_ROW_HEIGHT_DP / 2).dp)
                            .clickable(titleButton.onClick),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(titleButton.icon),
                            contentDescription = titleButton.description,
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                            modifier = GlanceModifier.size(20.dp)
                        )
                    }
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
                // SDK_INT compared directly, not through a local copy: lint can't follow one
                // and flags these calls as unguarded (#357).
                if (colors is WidgetColorSource.Wallpaper && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setColorStateList(R.id.widget_verse_text, "setTextColor", R.color.widget_verse_text)
                } else if (colors is WidgetColorSource.Palette && colors.isDark == null &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ) {
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

/** The wallpaper's colors (Material You), or the app's color theme (#333): fixed to light or
 *  dark when the app is, both otherwise so Glance follows the phone's mode. */
internal fun WidgetColorSource.glanceColors(): ColorProviders = when (this) {
    WidgetColorSource.Wallpaper -> DynamicThemeColorProviders
    is WidgetColorSource.Palette -> if (isDark == null) {
        ColorProviders(light = palette.colorScheme(isDark = false), dark = palette.colorScheme(isDark = true))
    } else {
        ColorProviders(palette.colorScheme(isDark))
    }
}

private val PADDING_HORIZONTAL = WIDGET_PADDING_HORIZONTAL_DP.dp
private val PADDING_VERTICAL = 12.dp
private val PADDING_TOP_WITH_TITLE = 4.dp
