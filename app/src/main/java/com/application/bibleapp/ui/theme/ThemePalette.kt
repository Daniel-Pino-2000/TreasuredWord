package com.application.bibleapp.ui.theme

import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.application.bibleapp.R

/**
 * The app's color themes (#244), picked in Settings › Appearance. Separate from [ThemeMode], so
 * each one has a light and a dark version. Saved by [name] in BibleRepository, so never rename a
 * constant; [PARCHMENT] is the default and the fallback for an unknown stored value.
 *
 * The splash styles are the startup screen Android 13+ shows on the next launch (#135), one per
 * mode so it matches what the app is about to draw.
 */
enum class ThemePalette(
    @StringRes val labelRes: Int,
    internal val light: ThemeColors,
    internal val dark: ThemeColors,
    @StyleRes val splashSystem: Int,
    @StyleRes val splashLight: Int,
    @StyleRes val splashDark: Int,
) {
    PARCHMENT(
        R.string.palette_parchment, ParchmentLight, ParchmentDark,
        R.style.Theme_BibleApp_Splash, R.style.Theme_BibleApp_SplashLight, R.style.Theme_BibleApp_SplashDark
    ),
    INK(
        R.string.palette_ink, InkLight, InkDark,
        R.style.Theme_BibleApp_SplashInk, R.style.Theme_BibleApp_SplashInkLight, R.style.Theme_BibleApp_SplashInkDark
    ),
    GRAPHITE(
        R.string.palette_graphite, GraphiteLight, GraphiteDark,
        R.style.Theme_BibleApp_SplashGraphite, R.style.Theme_BibleApp_SplashGraphiteLight, R.style.Theme_BibleApp_SplashGraphiteDark
    ),
    SAGE(
        R.string.palette_sage, SageLight, SageDark,
        R.style.Theme_BibleApp_SplashSage, R.style.Theme_BibleApp_SplashSageLight, R.style.Theme_BibleApp_SplashSageDark
    ),
    SLATE(
        R.string.palette_slate, SlateLight, SlateDark,
        R.style.Theme_BibleApp_SplashSlate, R.style.Theme_BibleApp_SplashSlateLight, R.style.Theme_BibleApp_SplashSlateDark
    ),
    ROSE(
        R.string.palette_rose, RoseLight, RoseDark,
        R.style.Theme_BibleApp_SplashRose, R.style.Theme_BibleApp_SplashRoseLight, R.style.Theme_BibleApp_SplashRoseDark
    );

    private val lightScheme by lazy { light.toColorScheme(isDark = false) }
    private val darkScheme by lazy { dark.toColorScheme(isDark = true) }

    fun colorScheme(isDark: Boolean): ColorScheme = if (isDark) darkScheme else lightScheme

    fun splashTheme(mode: ThemeMode): Int = when (mode) {
        ThemeMode.SYSTEM -> splashSystem
        ThemeMode.LIGHT -> splashLight
        ThemeMode.DARK -> splashDark
    }

    companion object {
        fun fromStored(name: String?): ThemePalette =
            entries.firstOrNull { it.name == name } ?: PARCHMENT
    }
}

/** Secondary and tertiary copy primary, as the original palette did: the app has one accent per
 *  theme, and leaving them unset falls back to M3's purple. tertiaryContainer is the accent tint,
 *  not the selection color, because the Bible Helper's crisis card is drawn on it. */
internal fun ThemeColors.toColorScheme(isDark: Boolean): ColorScheme = if (isDark) {
    darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = primary,
        onSecondary = onPrimary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = primary,
        onTertiary = onPrimary,
        tertiaryContainer = primaryContainer,
        onTertiaryContainer = onPrimaryContainer,
        background = background,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = primary,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        error = error,
        onError = onError,
        outline = outline,
        outlineVariant = outlineVariant,
        surfaceBright = surfaceBright,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceDim = surfaceDim,
    )
} else {
    lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = primary,
        onSecondary = onPrimary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = primary,
        onTertiary = onPrimary,
        tertiaryContainer = primaryContainer,
        onTertiaryContainer = onPrimaryContainer,
        background = background,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = primary,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        error = error,
        onError = onError,
        outline = outline,
        outlineVariant = outlineVariant,
        surfaceBright = surfaceBright,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceDim = surfaceDim,
    )
}
