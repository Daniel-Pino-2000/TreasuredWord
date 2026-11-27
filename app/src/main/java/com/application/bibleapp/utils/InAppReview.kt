package com.application.bibleapp.utils

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.application.bibleapp.data.repository.dailyVerseDateKey
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.review.ktx.launchReview
import com.google.android.play.core.review.ktx.requestReview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** At most one rating card every 90 days from the app's side, on top of Play's own hidden quota (#426). */
internal const val REVIEW_ASK_INTERVAL_MS = 90L * 24 * 60 * 60 * 1000

/** How many different days the app has to have been opened on before it may ask (#426), so
 *  never in someone's first days with it. */
internal const val REVIEW_MIN_DAYS_USED = 3

/** Time in the reader, since Home was last on screen, that counts as having read (#426): about a chapter. */
internal const val REVIEW_MIN_READING_MS = 60_000L

/** What the rating prompt remembers (#426), kept in SharedPreferences. */
internal data class ReviewPromptState(
    /** Different days the app came to the front on. */
    val daysUsed: Int = 0,
    /** The last of those days, as a [dailyVerseDateKey]. */
    val lastDayUsed: String? = null,
    /** When the app last asked Play for its card; 0 if never. */
    val lastAskedAtMs: Long = 0L
)

/** [this] after the app came to the front on [dayKey]: a new day counts once. */
internal fun ReviewPromptState.usedOn(dayKey: String): ReviewPromptState =
    if (dayKey == lastDayUsed) this else copy(daysUsed = daysUsed + 1, lastDayUsed = dayKey)

/**
 * Whether a good moment may ask Play for its rating card (#426): only from the
 * [REVIEW_MIN_DAYS_USED]th day of use, and not again for [REVIEW_ASK_INTERVAL_MS]. A clock that
 * went backwards ends the wait rather than stretching it (as in [shouldOfferUpdate]); Play's own
 * quota still keeps the card rare.
 */
internal fun shouldAskForReview(state: ReviewPromptState, nowMs: Long): Boolean =
    state.daysUsed >= REVIEW_MIN_DAYS_USED &&
        (state.lastAskedAtMs == 0L || nowMs < state.lastAskedAtMs || nowMs - state.lastAskedAtMs >= REVIEW_ASK_INTERVAL_MS)

/**
 * Asks Play for its rating card and shows it over [activity] (#426). [stillWanted] is checked once
 * Play answers, since that takes a moment and the person may have moved on; [onAsked] runs just
 * before the card is launched, so the 90-day wait starts. Returns whether the flow was launched.
 *
 * Play never says whether a card actually appeared or what was chosen, and may show nothing (its
 * quota, or a build not installed from Play), so nothing here depends on it. A failure (offline,
 * no Play Store) is ignored and doesn't start the wait.
 */
internal suspend fun runReviewFlow(
    manager: ReviewManager,
    activity: Activity,
    stillWanted: () -> Boolean,
    onAsked: () -> Unit
): Boolean {
    val info = try {
        manager.requestReview()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return false
    }
    if (!stillWanted()) return false
    onAsked()
    try {
        manager.launchReview(activity, info)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Already counted as asked: a card that failed to open isn't retried at once.
    }
    return true
}

/**
 * Google Play's in-app review (#426): Play's own small rating card, shown over the app at a good
 * moment, never on first launch, mid-task or after an error. There's no question of ours before
 * it, no reward and nothing locked behind it (Google's policy); the Settings row "Rate
 * TreasuredWord" is there for anyone who wants to rate on their own.
 *
 * The good moments: a Memorize practice reaching its summary with a game passed
 * ([onPracticePassed]), and coming back to Home after reading a while ([onHomeShown]). Either one
 * only asks from the [REVIEW_MIN_DAYS_USED]th day the app was opened on, at most once every
 * [REVIEW_ASK_INTERVAL_MS], and only while the app is in front.
 *
 * Create it in `onCreate`: it counts the days the app comes to the front on.
 */
class InAppReview internal constructor(
    private val activity: ComponentActivity,
    private val manager: ReviewManager
) : DefaultLifecycleObserver {
    constructor(activity: ComponentActivity) : this(activity, ReviewManagerFactory.create(activity))

    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var asking = false
    /** Time spent in the reader since Home was last on screen. Not saved: a restart starts over. */
    private var readingMs = 0L

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        save(load().usedOn(dailyVerseDateKey()))
    }

    /** The reader was on screen for [ms]. */
    fun addReadingTime(ms: Long) {
        if (ms > 0) readingMs += ms
    }

    /**
     * Home is on screen again: a good moment if the person read a while since it last was, and the
     * last chapter loaded without a problem ([chapterLoadedFine]). The reading time starts over
     * either way.
     */
    fun onHomeShown(chapterLoadedFine: Boolean) {
        val read = readingMs >= REVIEW_MIN_READING_MS
        readingMs = 0L
        if (read && chapterLoadedFine) goodMoment()
    }

    /** A Memorize practice reached its summary page with at least one game passed. */
    fun onPracticePassed() = goodMoment()

    private fun isInFront() = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun goodMoment() {
        if (asking || !isInFront() || !shouldAskForReview(load(), System.currentTimeMillis())) return
        asking = true
        activity.lifecycleScope.launch {
            try {
                runReviewFlow(
                    manager = manager,
                    activity = activity,
                    stillWanted = ::isInFront,
                    onAsked = { save(load().copy(lastAskedAtMs = System.currentTimeMillis())) }
                )
            } finally {
                asking = false
            }
        }
    }

    private fun load() = ReviewPromptState(
        daysUsed = prefs.getInt(KEY_DAYS_USED, 0),
        lastDayUsed = prefs.getString(KEY_LAST_DAY_USED, null),
        lastAskedAtMs = prefs.getLong(KEY_LAST_ASKED_AT, 0L)
    )

    private fun save(state: ReviewPromptState) = prefs.edit {
        putInt(KEY_DAYS_USED, state.daysUsed)
        putString(KEY_LAST_DAY_USED, state.lastDayUsed)
        putLong(KEY_LAST_ASKED_AT, state.lastAskedAtMs)
    }

    private companion object {
        const val PREFS = "in_app_review"
        const val KEY_DAYS_USED = "days_used"
        const val KEY_LAST_DAY_USED = "last_day_used"
        const val KEY_LAST_ASKED_AT = "last_asked_at_ms"
    }
}

/** The activity's [InAppReview], for the screens with a good moment (#426); null in previews. */
val LocalInAppReview = staticCompositionLocalOf<InAppReview?> { null }
