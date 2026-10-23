package com.application.bibleapp.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.annotation.ColorInt
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.application.bibleapp.R

/** docs/privacy-policy.md as GitHub Pages publishes it; the same URL is in the Play Console. */
const val PRIVACY_POLICY_URL = "https://daniel-pino-2000.github.io/TreasuredWord/privacy/"

/**
 * Every outside link in the app goes through here (#46): GotQuestions.org pages open in a Chrome
 * Custom Tab, a browser sheet over the app that Back closes, and BibleProject videos open in the
 * YouTube app. Never a WebView, so nobody else's page is framed or altered.
 */
class ExternalLinks internal constructor(
    private val context: Context,
    @ColorInt private val barColor: Int,
    private val darkTheme: Boolean,
    private val onFailure: (UiText) -> Unit
) {
    /** [url] must be https — every caller passes one of our own fixed links. */
    fun openPage(url: String) {
        require(url.startsWith("https://")) { "Outside links must be https: $url" }
        if (!NetworkUtils.isOnline(context)) return onFailure(UiText.Res(R.string.external_link_offline))

        val uri = url.toUri()
        // No Custom Tabs browser installed: a plain link still opens in whatever browser there is.
        if (CustomTabsClient.getPackageName(context, null) == null) return startOrReport(Intent(Intent.ACTION_VIEW, uri))

        // The bars follow the app's own theme, not the phone's dark-mode setting (same rule as #24).
        val colors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(barColor)
            .setNavigationBarColor(barColor)
            .build()
        val customTab = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(colors)
            .setColorScheme(if (darkTheme) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_ON)
            .build()
        try {
            customTab.launchUrl(context, uri)
        } catch (_: ActivityNotFoundException) {
            startOrReport(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    /** In the YouTube app when it's installed, otherwise on youtube.com through [openPage]. */
    fun openYouTubeVideo(videoId: String) {
        if (!NetworkUtils.isOnline(context)) return onFailure(UiText.Res(R.string.external_link_offline))
        val url = "https://www.youtube.com/watch?v=$videoId"
        val app = Intent(Intent.ACTION_VIEW, url.toUri()).setPackage(YOUTUBE_PACKAGE)
        try {
            context.startActivity(app)
        } catch (_: ActivityNotFoundException) {
            openPage(url)
        }
    }

    private fun startOrReport(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            onFailure(UiText.Res(R.string.external_link_no_app))
        }
    }

    private companion object {
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    }
}

/** Whether the UI is showing in Spanish (picked in the app or the phone's language), which is
 *  the one language GotQuestions.org and BibleProject have checked Spanish links for. */
@Composable
fun isSpanishUi(): Boolean = LocalConfiguration.current.locales[0].language == "es"

/** [onFailure] gets the message for a snackbar when a link can't open (offline, no browser). */
@Composable
fun rememberExternalLinks(onFailure: (UiText) -> Unit): ExternalLinks {
    val context = LocalContext.current
    // Same color as the app's own top bar, so the tab reads as part of the app.
    val barColor = MaterialTheme.colorScheme.background
    val currentOnFailure by rememberUpdatedState(onFailure)
    return remember(context, barColor) {
        ExternalLinks(
            context = context,
            barColor = barColor.toArgb(),
            darkTheme = barColor.luminance() < 0.5f,
            onFailure = { currentOnFailure(it) }
        )
    }
}
