package com.application.bibleapp.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import android.content.res.Resources
import com.application.bibleapp.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.text.DateFormat

/**
 * ISO-8601 UTC instant, matching server/docs/api_contract.md's timestamp convention. This app's
 * minSdk (24) is below java.time.Instant's native availability (API 26) and core library
 * desugaring isn't enabled, so this formats manually instead of pulling in a new dependency or
 * toolchain change just for timestamps.
 */
fun isoTimestampNow(): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date())
}

/** A stored ISO-8601 UTC timestamp (see [isoTimestampNow]) as a short date in [locale]'s own
 *  format, e.g. "Sep 18, 2026" or "18 sept 2026" — for the Library screen's highlight/note cards.
 *  Falls back to the raw string on a malformed timestamp rather than crashing the list over one
 *  bad row. A fresh [SimpleDateFormat] per call, same as [isoTimestampNow] — the class isn't
 *  thread-safe to share. */
fun formatDisplayDate(iso: String, locale: Locale): String {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val display = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
    return runCatching { display.format(parser.parse(iso)!!) }.getOrDefault(iso)
}

/** [formatDisplayDate] in the app language. */
@Composable
fun displayDate(iso: String): String = formatDisplayDate(iso, LocalConfiguration.current.locales[0])

/** A stored ISO-8601 UTC timestamp (see [isoTimestampNow]) as the sync status line — "Synced just
 *  now", "Synced 5m ago", "Synced 3h ago" — for Settings and the You tab (Phase G). Falls back to
 *  [formatDisplayDate] past a day, since "37h ago" reads worse than a calendar date. */
fun formatSyncedStatus(resources: Resources, iso: String): String {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val parsed = runCatching { parser.parse(iso) }.getOrNull()
        ?: return resources.getString(R.string.sync_status_on_date, formatDisplayDate(iso, resources.configuration.locales[0]))
    val minutes = ((Date().time - parsed.time) / 60000).toInt()
    return when {
        minutes < 1 -> resources.getString(R.string.sync_status_just_now)
        minutes < 60 -> resources.getQuantityString(R.plurals.sync_status_minutes_ago, minutes, minutes)
        minutes < 24 * 60 -> (minutes / 60).let { hours ->
            resources.getQuantityString(R.plurals.sync_status_hours_ago, hours, hours)
        }
        else -> resources.getString(R.string.sync_status_on_date, formatDisplayDate(iso, resources.configuration.locales[0]))
    }
}
