package com.application.bibleapp.components

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.application.bibleapp.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay

/**
 * True once [waiting] has stayed true for [afterMillis]. A request to our server normally takes
 * well under a second, so a wait this long almost always means it was asleep and is starting
 * up (#87). Goes back to false as soon as [waiting] does.
 */
@Composable
fun rememberWaitingLong(waiting: Boolean, afterMillis: Long = SERVER_SLOW_AFTER_MILLIS): Boolean {
    var waitingLong by remember { mutableStateOf(false) }
    LaunchedEffect(waiting) {
        waitingLong = false
        if (waiting) {
            delay(afterMillis)
            waitingLong = true
        }
    }
    return waitingLong
}

/**
 * Whether to show a progress indicator for [loading], without the split-second flash a quick
 * load gives (#337): it shows only once [loading] has lasted [showAfterMillis], and once shown it
 * stays at least [minShownMillis], like Android's `ContentLoadingProgressBar`. A load that stops
 * and starts again while it's shown keeps it up without a gap.
 */
@Composable
fun rememberDelayedProgress(
    loading: Boolean,
    showAfterMillis: Long = PROGRESS_SHOW_AFTER_MILLIS,
    minShownMillis: Long = PROGRESS_MIN_SHOWN_MILLIS
): Boolean {
    var visible by remember { mutableStateOf(false) }
    var shownAtMillis by remember { mutableLongStateOf(0L) }
    LaunchedEffect(loading) {
        val change = nextProgressChange(
            loading = loading,
            visible = visible,
            shownForMillis = SystemClock.uptimeMillis() - shownAtMillis,
            showAfterMillis = showAfterMillis,
            minShownMillis = minShownMillis
        ) ?: return@LaunchedEffect
        delay(change.afterMillis)
        visible = change.visible
        if (change.visible) shownAtMillis = SystemClock.uptimeMillis()
    }
    return visible
}

/** A change [rememberDelayedProgress] makes after [afterMillis], unless [loading] changes first. */
internal data class ProgressChange(val visible: Boolean, val afterMillis: Long)

/**
 * The next change to a delayed progress indicator, or null when it should stay as it is (#337).
 * [shownForMillis] is how long it has been visible, and only matters while [visible].
 */
internal fun nextProgressChange(
    loading: Boolean,
    visible: Boolean,
    shownForMillis: Long,
    showAfterMillis: Long = PROGRESS_SHOW_AFTER_MILLIS,
    minShownMillis: Long = PROGRESS_MIN_SHOWN_MILLIS
): ProgressChange? = when {
    loading && !visible -> ProgressChange(visible = true, afterMillis = showAfterMillis)
    !loading && visible -> ProgressChange(
        visible = false,
        afterMillis = (minShownMillis - shownForMillis).coerceAtLeast(0L)
    )
    else -> null
}

/** Explains a long wait on our server, so the app doesn't look frozen while Render wakes it (#87). */
@Composable
fun ServerWakeHint(modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Text(
        text = stringResource(R.string.server_waking_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
        modifier = modifier
    )
}

private const val SERVER_SLOW_AFTER_MILLIS = 5_000L

// Material shows progress only for a noticeable wait; 500 ms each way is ContentLoadingProgressBar's.
internal const val PROGRESS_SHOW_AFTER_MILLIS = 500L
internal const val PROGRESS_MIN_SHOWN_MILLIS = 500L
