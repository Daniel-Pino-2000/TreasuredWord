package com.application.bibleapp.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
