package com.application.bibleapp.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The app's own UI language, separate from the Bible translation's language (which decides the
 * scripture text and book names). AppCompat owns the choice: on Android 13+ it's the system's
 * per-app language, also editable from the phone's own settings; below that AppCompat stores it
 * itself (AppLocalesMetadataHolderService with autoStoreLocales, see the manifest) and applies it
 * to each activity it creates.
 */
object AppLanguage {
    /**
     * Languages with a translated values folder, in picker order (alphabetical by their own name).
     * Keep in step with res/. Some folders don't spell the tag the same way: Indonesian "id" is
     * values-in, Filipino "fil" is values-b+fil (values-tl would only match the old "tl"), and
     * Simplified Chinese "zh-Hans" is values-b+zh+Hans. Portuguese is "pt-BR" here because
     * values-pt is written in Brazilian Portuguese.
     */
    val supportedTags = listOf("de", "en", "es", "fil", "fr", "id", "sw", "pt-BR", "ko", "zh-Hans")

    private const val PREFS = "app_language"
    private const val KEY_TAG = "tag"

    /** The supported language in effect by the user's choice, or null for "System default". A
     *  regional choice made in the phone's settings ("es-419") matches its base language ("es"). */
    fun currentTag(): String? {
        val chosen = AppCompatDelegate.getApplicationLocales()[0] ?: return null
        return supportedTags.firstOrNull { matches(Locale.forLanguageTag(it), chosen) }
            ?: chosen.toLanguageTag()
    }

    /** The supported tag for the language the app is showing in [locale], or "en" — what the
     *  Bible Helper answers in (#127). Unlike [currentTag], "System default" resolves too. */
    fun answerLanguageTag(locale: Locale): String =
        supportedTags.firstOrNull { matches(Locale.forLanguageTag(it), locale) } ?: "en"

    /** Same language, and same script when the supported one names it — so Traditional Chinese
     *  ("zh-TW", "zh-Hant") isn't shown as Simplified. A chosen locale without a script counts
     *  as Simplified unless its region writes Traditional. */
    private fun matches(supported: Locale, chosen: Locale): Boolean {
        if (supported.language != chosen.language) return false
        if (supported.script.isEmpty()) return true
        val chosenScript = chosen.script.ifEmpty {
            if (chosen.language == "zh" && chosen.country in setOf("TW", "HK", "MO")) "Hant" else "Hans"
        }
        return chosenScript == supported.script
    }

    /** [tag] null means "System default". MainActivity switches in place, without being recreated
     *  (configChanges="locale" in the manifest). */
    fun select(context: Context, tag: String?) {
        // A copy for code that runs with no activity (the daily verse worker) below Android 13,
        // where AppCompat only applies the language to activities.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_TAG, tag) }
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        )
    }

    /** A language's name written in that language — "English", "Español". A tag that names a
     *  script goes by the script's own name, "简体中文", rather than the doubled "中文 (简体中文)". */
    fun displayName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        val name = if (locale.script.isNotEmpty() && locale.country.isEmpty()) {
            locale.getDisplayScript(locale)
        } else {
            locale.getDisplayName(locale)
        }
        return name.replaceFirstChar { it.titlecase(locale) }
    }

    /** [context] in the app language, for text built outside an activity (notifications). On
     *  Android 13+ the system already gives every context of the app its chosen language. */
    fun localizedContext(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val tag = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)
            ?: return context
        val config = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(tag))
        }
        return context.createConfigurationContext(config)
    }
}
