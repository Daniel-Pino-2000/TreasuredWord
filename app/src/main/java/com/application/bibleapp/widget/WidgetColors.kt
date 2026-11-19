package com.application.bibleapp.widget

import android.os.Build
import androidx.annotation.StringRes
import com.application.bibleapp.R
import com.application.bibleapp.ui.theme.ThemeMode
import com.application.bibleapp.ui.theme.ThemePalette

/**
 * Where the daily verse widget takes its colors from (#333), picked in Settings › Appearance.
 * [APP] by default: the app's color theme and its Light/Dark/System setting. [WALLPAPER] is
 * Android's dynamic color ("Material You"), which only exists on Android 12+, so below that the
 * setting isn't offered and the widget always follows the app. Saved by [name] in
 * BibleRepository, so never rename a constant.
 */
enum class WidgetColors(@StringRes val labelRes: Int) {
    APP(R.string.widget_colors_app),
    WALLPAPER(R.string.widget_colors_wallpaper);

    companion object {
        fun fromStored(name: String?): WidgetColors = entries.firstOrNull { it.name == name } ?: APP

        fun isWallpaperAvailable(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.S
    }
}

/** What the widget is actually drawn with, worked out from the saved choices. */
sealed interface WidgetColorSource {
    /** The wallpaper's colors; light or dark follows the phone. */
    data object Wallpaper : WidgetColorSource

    /** One of the app's color themes. [isDark] is null when the app is on System: then light or
     *  dark follows the phone, since the home screen is the phone's. */
    data class Palette(val palette: ThemePalette, val isDark: Boolean?) : WidgetColorSource
}

fun widgetColorSource(choice: WidgetColors, palette: ThemePalette, mode: ThemeMode, sdkInt: Int): WidgetColorSource =
    if (choice == WidgetColors.WALLPAPER && WidgetColors.isWallpaperAvailable(sdkInt)) {
        WidgetColorSource.Wallpaper
    } else {
        WidgetColorSource.Palette(
            palette,
            when (mode) {
                ThemeMode.SYSTEM -> null
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
        )
    }
