package com.application.bibleapp.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
