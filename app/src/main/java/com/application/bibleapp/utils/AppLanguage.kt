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
    /** Languages with a translated values-xx folder, in picker order. Keep in step with res/. */
    val supportedTags = listOf("en", "es")

    private const val PREFS = "app_language"
    private const val KEY_TAG = "tag"

    /** The supported language in effect by the user's choice, or null for "System default". A
     *  regional choice made in the phone's settings ("es-419") matches its base language ("es"). */
    fun currentTag(): String? {
        val chosen = AppCompatDelegate.getApplicationLocales()[0] ?: return null
        return supportedTags.firstOrNull { Locale.forLanguageTag(it).language == chosen.language }
            ?: chosen.toLanguageTag()
    }

    /** [tag] null means "System default". Recreates the activity in the new language. */
    fun select(context: Context, tag: String?) {
        // A copy for code that runs with no activity (the daily verse worker) below Android 13,
        // where AppCompat only applies the language to activities.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_TAG, tag) }
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        )
    }

    /** A language's name written in that language — "English", "Español". */
    fun displayName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
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
