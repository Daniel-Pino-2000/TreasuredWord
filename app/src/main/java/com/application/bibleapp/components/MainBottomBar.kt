package com.application.bibleapp.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.data.model.chapterCount
import com.application.bibleapp.navigation.Screen
import com.application.bibleapp.navigation.bottomNavigationItems
import com.application.bibleapp.viewmodel.BibleViewModel
import kotlin.math.roundToInt

/**
 * The whole bottom-of-screen chrome — the chapter nav row and the tab navigation row —
 * reads as one cohesive group rather than two stacked strips:
 * - A single outer [Surface] supplies one background color and one base tonal elevation
 *   for the entire group (nav row, tab row, and the gap the system nav bar shows through
 *   via [navigationBarsPadding]), so there's no color or shadow seam between them.
 *   [NavigationBar] below no longer paints its own container color for the same reason.
 * - [ChapterNavBar] — arrows plus the "you are here" book/chapter pill in one row — stays
 *   visible even once the tab row recedes on scroll, sitting at a slightly higher tonal
 *   elevation than its surroundings so it reads as the anchor the rest of the chrome is
 *   arranged around, with a hairline divider marking it off from the tab row below.
 * - [chromeHiddenFraction] — a single, already-smoothed and debounced 0..1 value (see
 *   MainActivity) — drives the tab row through [CollapsingSection]; [ChapterNavBar] never
 *   collapses, so chapter navigation stays reachable through the whole scroll gesture.
 *
 * [navigationBarsPadding] is applied once, here, at the outermost content level — not
 * inside [NavigationBar] (its own default inset handling is turned off via `windowInsets =
 * WindowInsets(0)`) — so there's exactly one place reserving room for the system
 * navigation bar, and it still applies to [ChapterNavBar] even after the tab row has
 * fully collapsed out from under it. Crucially, the padding sits *inside* the outer
 * [Surface], not on the [Surface] itself: a `Surface(modifier = Modifier
 * .navigationBarsPadding())` would shrink the Surface's own painted bounds by the inset,
 * leaving that strip uncolored — exactly the strip the transparent, edge-to-edge system
 * nav bar sits over. Painting the [Surface] full-bleed and padding only its content is
 * what makes this surface color the one that actually shows through behind the system bar,
 * so the transition into its own space is seamless rather than a color break.
 *
 * [hideBar] — true while a reading-screen `ModalBottomSheet` (footnote/highlight/note) is open
 * — collapses the *entire* group (chapter nav row included, which otherwise never collapses on
 * scroll) so the sheet reaches a clean bottom edge instead of sitting above a still-visible bar.
 * It's animated, not instant, and independent of [chromeHiddenFraction]: the tab row hides if
 * either one calls for it, but the chapter nav row only ever responds to [hideBar].
 */
@Composable
fun MainBottomBar(
    currentRoute: String?,
    bibleViewModel: BibleViewModel,
    hideBar: Boolean,
    chromeHiddenFraction: Float = 0f,
    onItemSelected: (String) -> Unit,
    onBookPickerClicked: () -> Unit
) {
    val sheetHiddenFraction by animateFloatAsState(targetValue = if (hideBar) 1f else 0f, label = "bottomBarSheetHide")

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = NavigationBarDefaults.Elevation
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            if (currentRoute == Screen.Bible.route) {
                val currentBook by bibleViewModel.currentBook.collectAsState()
                val currentChapter by bibleViewModel.currentChapter.collectAsState()
                val bookNames by bibleViewModel.bookNames.collectAsState()
                val chapterStructure by bibleViewModel.chapterStructure.collectAsState()
                val canGoPrevious = currentBook > 1 || currentChapter > 1
                val canGoNext = currentChapter < chapterStructure.chapterCount(currentBook) ||
                    currentBook < BibleBooks.allBooks.size

                CollapsingSection(collapsedFraction = sheetHiddenFraction) {
                    Column {
                        // A touch more tonal elevation than its surroundings — the row reads as
                        // slightly "raised" relative to the tab row around it, the same system M3
                        // already uses to signal elevation, no new color introduced.
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = NavigationBarDefaults.Elevation + 2.dp
                        ) {
                            ChapterNavBar(
                                currentBook = currentBook,
                                bookName = { bookId -> bookNames[bookId] ?: BibleBooks.getBookById(bookId)?.name ?: "Unknown" },
                                currentChapter = currentChapter,
                                canGoPrevious = canGoPrevious,
                                canGoNext = canGoNext,
                                onPrevious = { bibleViewModel.previousChapter() },
                                onNext = { bibleViewModel.nextChapter() },
                                onSelectBook = onBookPickerClicked
                            )
                        }

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            thickness = 0.5.dp
                        )
                    }
                }
            }

            CollapsingSection(collapsedFraction = maxOf(chromeHiddenFraction, sheetHiddenFraction)) {
                NavigationBar(
                    // Transparent: the color for this whole group comes from the single
                    // outer Surface above, so the tab row blends into it with no seam.
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    // Insets for the system nav bar are reserved once, at the MainBottomBar
                    // level, so they still apply once this row has collapsed away — see the
                    // class doc above.
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    bottomNavigationItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                onItemSelected(item.route)
                            },
                            icon = {
                                Icon(
                                    imageVector = if (currentRoute == item.route) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.title
                                )
                            },
                            label = {
                                Text(item.title)
                            },

                            )
                    }
                }
            }
        }
    }
}

/**
 * Shrinks its content's reported height to `0` at [collapsedFraction] `1f` (not just a
 * visual offset), so the Scaffold reclaims the freed space and the reading content below
 * can expand into it, and fades it out over the same range. Used for the tab row.
 */
@Composable
private fun CollapsingSection(
    collapsedFraction: Float,
    content: @Composable () -> Unit
) {
    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds(),
        content = {
            Box(modifier = Modifier.alpha(1f - collapsedFraction)) {
                content()
            }
        }
    ) { measurables, constraints ->
        // Measure at natural height (ignore the incoming height constraint) so shrinking
        // the reported layout height below never squashes the content.
        val placeable = measurables.first().measure(
            constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        )
        val visibleHeight = (placeable.height * (1f - collapsedFraction))
            .roundToInt()
            .coerceIn(0, placeable.height)

        layout(placeable.width, visibleHeight) {
            // Anchor to the bottom of the shrinking box so the screen-edge-most (most
            // reachable) part of the content is the last to disappear as it collapses.
            placeable.placeRelative(0, visibleHeight - placeable.height)
        }
    }
}
