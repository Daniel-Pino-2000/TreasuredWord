package com.application.bibleapp.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
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
 * Always occupies Scaffold's `bottomBar` slot on the reading screen, selection or not —
 * [VerseSelectionToolbar] floats as a separate overlay above it instead of taking this slot over,
 * so this bar's own height (and therefore Scaffold's content inset) never changes just because a
 * selection started or ended. [modifier] carries [androidx.compose.ui.layout.onSizeChanged] from
 * MainActivity so that overlay knows how high above this bar's current top edge to sit.
 */
@Composable
fun MainBottomBar(
    currentRoute: String?,
    bibleViewModel: BibleViewModel,
    chromeHiddenFraction: Float = 0f,
    onItemSelected: (String) -> Unit,
    onBookPickerClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = NavigationBarDefaults.Elevation
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            if (currentRoute == Screen.Bible.route) {
                // A touch more tonal elevation than its surroundings — the row reads as
                // slightly "raised" relative to the tab row around it, the same system M3
                // already uses to signal elevation, no new color introduced.
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = NavigationBarDefaults.Elevation + 2.dp
                ) {
                    BibleChapterNav(bibleViewModel = bibleViewModel, onSelectBook = onBookPickerClicked)
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.5.dp
                )
            }

            CollapsingSection(collapsedFraction = chromeHiddenFraction) {
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
 * [ChapterNavBar] wired to [bibleViewModel]'s current position — shared by [MainBottomBar]
 * (portrait) and the Bible top bar's title slot in compact height ([compact], see MainActivity).
 */
@Composable
fun BibleChapterNav(
    bibleViewModel: BibleViewModel,
    onSelectBook: () -> Unit,
    compact: Boolean = false
) {
    val currentBook by bibleViewModel.currentBook.collectAsState()
    val currentChapter by bibleViewModel.currentChapter.collectAsState()
    val bookNames by bibleViewModel.bookNames.collectAsState()
    val chapterStructure by bibleViewModel.chapterStructure.collectAsState()
    val canGoPrevious = currentBook > 1 || currentChapter > 1
    val canGoNext = currentChapter < chapterStructure.chapterCount(currentBook) ||
        currentBook < BibleBooks.allBooks.size

    ChapterNavBar(
        currentBook = currentBook,
        bookName = { bookId -> bookNames[bookId] ?: BibleBooks.getBookById(bookId)?.name ?: "Unknown" },
        currentChapter = currentChapter,
        canGoPrevious = canGoPrevious,
        canGoNext = canGoNext,
        onPrevious = { bibleViewModel.previousChapter() },
        onNext = { bibleViewModel.nextChapter() },
        onSelectBook = onSelectBook,
        compact = compact
    )
}

/**
 * [MainBottomBar]'s tabs as a side rail, used instead of it when the window height is compact
 * (a phone in landscape) — a bottom bar there eats a quarter of the height, a rail only some of
 * the far more plentiful width. Items are centered vertically so they're within thumb reach.
 * The rail's own default insets cover the status bar and a start-side display cutout.
 */
@Composable
fun MainNavigationRail(
    currentRoute: String?,
    onItemSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(NavigationBarDefaults.Elevation)
    ) {
        Spacer(modifier = Modifier.weight(1f))
        bottomNavigationItems.forEach { item ->
            NavigationRailItem(
                selected = currentRoute == item.route,
                onClick = { onItemSelected(item.route) },
                icon = {
                    Icon(
                        imageVector = if (currentRoute == item.route) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.title
                    )
                },
                label = { Text(item.title) }
            )
        }
        Spacer(modifier = Modifier.weight(1f))
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
