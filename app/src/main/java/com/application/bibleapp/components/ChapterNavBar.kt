package com.application.bibleapp.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.application.bibleapp.ui.theme.Spacing

/** Quick, subtle — reads as a nudge in the direction of travel, not a distraction. */
private const val CHAPTER_LABEL_ANIM_MS = 180

/**
 * The bottom chrome's one navigation row: previous-chapter arrow, the "you are here"
 * book/chapter pill, next-chapter arrow — arrows sit at the row's edges for one-handed
 * thumb reach, the pill stays centered and is the visually heavier of the two pieces
 * (filled with [MaterialTheme.colorScheme.primaryContainer] vs. the arrows' quieter
 * [MaterialTheme.colorScheme.surfaceVariant]) so the group reads as "arrows act on the
 * label between them," not three independent controls.
 *
 * The label cross-fades and slides a short distance on every chapter change — direction
 * follows the book/chapter delta (forward navigation slides left, matching how the arrow
 * that was tapped points), not just whichever arrow was tapped, so a jump from the
 * book/chapter picker still animates sensibly.
 *
 * [compact] is the landscape variant that lives in the top app bar's title slot (see
 * MainActivity): the three pieces sit snugly together at the start instead of spreading across
 * the full width, with smaller arrow buttons so it fits the bar's height.
 */
@Composable
fun ChapterNavBar(
    currentBook: Int,
    bookName: (Int) -> String,
    currentChapter: Int,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelectBook: () -> Unit,
    compact: Boolean = false
) {
    val arrowSize = if (compact) 40.dp else 48.dp
    Row(
        modifier = if (compact) {
            Modifier
        } else {
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (compact) Arrangement.spacedBy(Spacing.sm) else Arrangement.SpaceBetween
    ) {
        FilledTonalIconButton(
            onClick = onPrevious,
            enabled = canGoPrevious,
            modifier = Modifier.size(arrowSize),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous Chapter")
        }

        AnimatedContent(
            targetState = currentBook to currentChapter,
            transitionSpec = {
                val forward = ordinal(targetState) >= ordinal(initialState)
                val slideDistance = if (forward) 1 else -1
                (slideInHorizontally(tween(CHAPTER_LABEL_ANIM_MS)) { width -> slideDistance * width / 3 } +
                    fadeIn(tween(CHAPTER_LABEL_ANIM_MS))) togetherWith
                    (slideOutHorizontally(tween(CHAPTER_LABEL_ANIM_MS)) { width -> -slideDistance * width / 3 } +
                        fadeOut(tween(CHAPTER_LABEL_ANIM_MS)))
            },
            label = "chapterLabel"
        ) { (book, chapter) ->
            FilledTonalButton(
                onClick = onSelectBook,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Text(
                    text = "${bookName(book)} $chapter",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        FilledTonalIconButton(
            onClick = onNext,
            enabled = canGoNext,
            modifier = Modifier.size(arrowSize),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next Chapter")
        }
    }
}

/** A monotonic-enough sort key for (book, chapter) to tell which of two states comes later. */
private fun ordinal(position: Pair<Int, Int>): Int = position.first * 1000 + position.second
