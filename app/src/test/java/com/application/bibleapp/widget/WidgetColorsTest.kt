package com.application.bibleapp.widget

import com.application.bibleapp.ui.theme.ThemeMode
import com.application.bibleapp.ui.theme.ThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which colors the daily verse widget is drawn with (#333). */
class WidgetColorsTest {

    private val android12 = 31
    private val android11 = 30

    @Test
    fun `the app's theme is the default and the fallback for an unknown stored value`() {
        assertEquals(WidgetColors.APP, WidgetColors.fromStored(null))
        assertEquals(WidgetColors.APP, WidgetColors.fromStored("SOMETHING_ELSE"))
        assertEquals(WidgetColors.WALLPAPER, WidgetColors.fromStored("WALLPAPER"))
    }

    @Test
    fun `wallpaper colors are only offered from Android 12`() {
        assertTrue(WidgetColors.isWallpaperAvailable(android12))
        assertFalse(WidgetColors.isWallpaperAvailable(android11))
    }

    @Test
    fun `same as the app uses the chosen theme, with the app's light or dark`() {
        assertEquals(
            WidgetColorSource.Palette(ThemePalette.INK, isDark = true),
            widgetColorSource(WidgetColors.APP, ThemePalette.INK, ThemeMode.DARK, android12)
        )
        assertEquals(
            WidgetColorSource.Palette(ThemePalette.SAGE, isDark = false),
            widgetColorSource(WidgetColors.APP, ThemePalette.SAGE, ThemeMode.LIGHT, android12)
        )
    }

    @Test
    fun `with the app on System, light or dark is left to the phone`() {
        assertEquals(
            WidgetColorSource.Palette(ThemePalette.ROSE, isDark = null),
            widgetColorSource(WidgetColors.APP, ThemePalette.ROSE, ThemeMode.SYSTEM, android12)
        )
    }

    @Test
    fun `match wallpaper uses the wallpaper's colors whatever the app's theme`() {
        for (mode in ThemeMode.entries) {
            assertEquals(
                WidgetColorSource.Wallpaper,
                widgetColorSource(WidgetColors.WALLPAPER, ThemePalette.SLATE, mode, android12)
            )
        }
    }

    @Test
    fun `below Android 12 a stored wallpaper choice falls back to the app's theme`() {
        // A backup restored from a newer phone can carry WALLPAPER to one without dynamic color.
        assertEquals(
            WidgetColorSource.Palette(ThemePalette.GRAPHITE, isDark = false),
            widgetColorSource(WidgetColors.WALLPAPER, ThemePalette.GRAPHITE, ThemeMode.LIGHT, android11)
        )
    }
}
