package com.application.bibleapp.utils

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Text produced outside the UI (ViewModels, repositories) that the UI resolves when it shows it.
 * Holding a resource id instead of a finished String means the message follows the app language
 * in effect when it's displayed — a ViewModel outlives a language change, and its context's
 * resources aren't the Activity's on older Android versions anyway.
 *
 * [args] may themselves be [UiText]; they're resolved first, so a sentence can embed a plural.
 */
sealed interface UiText {
    /** Text that's already final — from the server, an exception, or user content. */
    data class Raw(val value: String) : UiText

    class Res(@StringRes val id: Int, vararg val args: Any) : UiText

    /** With no [args], [count] is also the only format argument ("%d verses"). */
    class Plural(@PluralsRes val id: Int, val count: Int, vararg val args: Any) : UiText

    fun resolve(resources: Resources): String = when (this) {
        is Raw -> value
        is Res -> resources.getString(id, *resolveArgs(resources, args))
        is Plural -> if (args.isEmpty()) {
            resources.getQuantityString(id, count, count)
        } else {
            resources.getQuantityString(id, count, *resolveArgs(resources, args))
        }
    }
}

private fun resolveArgs(resources: Resources, args: Array<out Any>): Array<Any> =
    Array(args.size) { i -> (args[i] as? UiText)?.resolve(resources) ?: args[i] }

@Composable
fun UiText.asString(): String {
    // Read so a configuration (locale) change recomposes callers, the same as stringResource.
    LocalConfiguration.current
    return resolve(LocalContext.current.resources)
}
