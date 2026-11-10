package com.application.bibleapp.verseimage

import android.content.Context
import android.content.Intent
import com.application.bibleapp.MainActivity

/** Set on the intent that opens the app straight into the verse image editor for today's verse
 *  (#256), from the daily verse notification or the home screen widget. */
const val EXTRA_SHARE_DAILY_VERSE_IMAGE = "com.treasuredword.app.extra.SHARE_DAILY_VERSE_IMAGE"

/**
 * Opens the app on Home with the verse image editor open for today's verse (#256). CLEAR_TASK like
 * the notification's and widget's own taps, so it always starts from Home whether the app was
 * closed or open on another screen, and [MainActivity] reads the extra in onCreate.
 */
fun dailyVerseImageIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        .putExtra(EXTRA_SHARE_DAILY_VERSE_IMAGE, true)
