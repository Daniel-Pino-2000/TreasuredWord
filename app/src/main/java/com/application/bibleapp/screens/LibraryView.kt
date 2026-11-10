package com.application.bibleapp.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import kotlin.math.abs
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.application.bibleapp.components.MissingVersionBanners
import com.application.bibleapp.components.SWATCH_NAMES
import com.application.bibleapp.components.SWATCH_TOUCH_SIZE
import com.application.bibleapp.components.HighlightSwatchCircle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ripple
import com.application.bibleapp.ui.theme.HighlightColors
import com.application.bibleapp.ui.theme.toHighlightArgb
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import com.application.bibleapp.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.application.bibleapp.data.model.Highlight
import com.application.bibleapp.data.model.Note
import com.application.bibleapp.data.remote.VerseLocationDto
import com.application.bibleapp.navigation.Screen
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.collapseUpward
import com.application.bibleapp.ui.theme.isCompactHeight
import com.application.bibleapp.ui.theme.rememberKeyboardOpenFraction
import com.application.bibleapp.utils.bookNameInVersion
import com.application.bibleapp.utils.displayDate
import com.application.bibleapp.utils.verseRefsText
import com.application.bibleapp.viewmodel.BibleViewModel
import com.application.bibleapp.viewmodel.VerseTextKey
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource

private enum class LibraryTab(@StringRes val labelRes: Int, val routeArg: String) {
    HIGHLIGHTS(R.string.label_highlights, Screen.Library.TAB_HIGHLIGHTS),
    NOTES(R.string.label_notes, Screen.Library.TAB_NOTES);

    companion object {
        fun fromRouteArg(arg: String?): LibraryTab = entries.firstOrNull { it.routeArg == arg } ?: HIGHLIGHTS
    }
}

/**
 * A single screen with a segmented Highlights/Notes control, rather than two routes — see
 * docs/UI_Integration_Roadmap.md Phase D. Reads straight from [BibleViewModel.libraryHighlights]/
 * [BibleViewModel.libraryNotes] (loaded once via [BibleViewModel.loadLibrary] when this screen
 * opens); nothing here needs the backend, so it's fully usable offline and signed out, same as
 * the highlighting/notes UI it's listing. [initialTab] lets Settings' two "My Library" rows
 * (Phase G) deep-link straight to either tab instead of always opening on Highlights.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryView(
    bibleViewModel: BibleViewModel,
    modifier: Modifier = Modifier,
    initialTab: String? = null,
    onOpenDetail: (type: String, localId: Long) -> Unit
) {
    LaunchedEffect(Unit) { bibleViewModel.loadLibrary() }

    val highlights by bibleViewModel.libraryHighlights.collectAsState()
    val notes by bibleViewModel.libraryNotes.collectAsState()
    val verseTextByKey by bibleViewModel.libraryVerseText.collectAsState()
    val bookNamesByVersion by bibleViewModel.libraryBookNames.collectAsState()
    val missingVersions by bibleViewModel.libraryMissingVersionLabels.collectAsState()
    val isRestoring by bibleViewModel.isRestoringContent.collectAsState()
    // Each item in its own translation's book names, like its text, and searched by that same
    // name (#54).
    val bookName = { versionId: String, bookId: Int -> bookNameInVersion(versionId, bookId, bookNamesByVersion) }

    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.fromRouteArg(initialTab)) }
    var query by rememberSaveable { mutableStateOf("") }
    // Stored highlight ARGBs to show; empty = every color. A List (not Set) so it's saveable.
    var colorFilter by rememberSaveable { mutableStateOf(emptyList<Int>()) }

    // A landscape phone with the keyboard up leaves ~110 dp: the tab row stacked on the search
    // field doesn't fit, so the tabs step aside while typing, like the top bar (#215, #228).
    // They collapse with the keyboard's own animation rather than popping out and back (#239).
    val compactHeight = isCompactHeight()
    val keyboardOpen = rememberKeyboardOpenFraction()

    // The window resizes for the keyboard (manifest): only the list gets shorter (#228).
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .collapseUpward { if (compactHeight) keyboardOpen.value else 0f }
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
        ) {
            LibraryTab.entries.forEachIndexed { index, tab ->
                SegmentedButton(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = LibraryTab.entries.size)
                ) {
                    Text(stringResource(tab.labelRes))
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = {
                Text(
                    stringResource(
                        if (selectedTab == LibraryTab.HIGHLIGHTS) R.string.library_search_by_book else R.string.library_search_notes
                    )
                )
            },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
        )

        when (selectedTab) {
            LibraryTab.HIGHLIGHTS -> {
                if (highlights.isNotEmpty()) {
                    HighlightColorFilterRow(
                        selected = colorFilter,
                        onToggle = { argb ->
                            colorFilter = if (argb in colorFilter) colorFilter - argb else colorFilter + argb
                        },
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
                    )
                }
                val filtered = remember(highlights, query, bookNamesByVersion, colorFilter) {
                    highlights.filter { h ->
                        (colorFilter.isEmpty() || h.color in colorFilter) &&
                            (query.isBlank() || h.verses.any { bookName(h.versionId, it.bookId).contains(query, ignoreCase = true) })
                    }
                }
                if (filtered.isEmpty()) {
                    EmptyLibraryState(
                        message = when {
                            highlights.isEmpty() && isRestoring -> stringResource(R.string.sync_restoring_content)
                            highlights.isEmpty() -> stringResource(R.string.library_empty_highlights)
                            query.isBlank() -> stringResource(R.string.library_no_highlights_in_colors)
                            else -> stringResource(R.string.library_no_highlights_match, query)
                        },
                        icon = rememberVectorPainter(Icons.Outlined.AutoStories)
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        item(key = MISSING_VERSIONS_KEY) {
                            MissingVersionBanners(bibleViewModel, versionIds = filtered.mapTo(mutableSetOf()) { it.versionId })
                        }
                        items(filtered, key = { it.localId }) { highlight ->
                            SwipeToDeleteRow(onDelete = { bibleViewModel.deleteLibraryHighlight(highlight) }) {
                                HighlightCard(
                                    highlight = highlight,
                                    verseText = resolveVerseText(highlight.versionId, highlight.verses, verseTextByKey),
                                    shownInKjv = highlight.versionId in missingVersions,
                                    bookName = { bookId -> bookName(highlight.versionId, bookId) },
                                    onClick = {
                                        onOpenDetail(Screen.LibraryItemDetail.TYPE_HIGHLIGHT, highlight.localId)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            LibraryTab.NOTES -> {
                val filtered = remember(notes, query, bookNamesByVersion) {
                    if (query.isBlank()) notes
                    else notes.filter { n ->
                        n.text.contains(query, ignoreCase = true) ||
                            n.verses.any { bookName(n.versionId, it.bookId).contains(query, ignoreCase = true) }
                    }
                }
                if (filtered.isEmpty()) {
                    EmptyLibraryState(
                        message = when {
                            notes.isEmpty() && isRestoring -> stringResource(R.string.sync_restoring_content)
                            notes.isEmpty() -> stringResource(R.string.library_empty_notes)
                            else -> stringResource(R.string.library_no_notes_match, query)
                        },
                        icon = painterResource(R.drawable.ic_stylus_note_outline)
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        item(key = MISSING_VERSIONS_KEY) {
                            MissingVersionBanners(bibleViewModel, versionIds = filtered.mapTo(mutableSetOf()) { it.versionId })
                        }
                        items(filtered, key = { it.localId }) { note ->
                            SwipeToDeleteRow(onDelete = { bibleViewModel.deleteLibraryNote(note) }) {
                                NoteCard(
                                    note = note,
                                    verseText = resolveVerseText(note.versionId, note.verses, verseTextByKey),
                                    shownInKjv = note.versionId in missingVersions,
                                    bookName = { bookId -> bookName(note.versionId, bookId) },
                                    onClick = {
                                        onOpenDetail(Screen.LibraryItemDetail.TYPE_NOTE, note.localId)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** LazyColumn key of the missing-translation banners above the cards — a String, so it can't
 *  collide with a card's Long local id. */
private const val MISSING_VERSIONS_KEY = "missing_versions"

/** Under a card's verse text while it's KJV's, standing in for a translation that isn't
 *  downloaded (#78). */
@Composable
internal fun ShownInKjvLabel(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.library_shown_in_kjv),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * Swipe-left (end-to-start) to delete (#243). It takes a real swipe: the card must be dragged past
 * [DELETE_SWIPE_FRACTION] of its width, and a short flick springs back instead of counting on its
 * speed alone. The background turns red with a haptic tick once letting go would delete, and back
 * when dragged back. The card slides fully off before [onDelete] runs (which shows Undo).
 * [enableDismissFromStartToEnd] is off so the back gesture at the screen edge never deletes, and
 * TalkBack users, who can't swipe, get a "Delete" action on the card instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeToDeleteRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val latestOnDelete by rememberUpdatedState(onDelete)
    var rowWidth by remember { mutableIntStateOf(0) }
    // confirmValueChange runs inside the state, so it reads the state back through this holder.
    val stateHolder = remember { arrayOfNulls<SwipeToDismissBoxState>(1) }
    val density = LocalDensity.current
    // Plain remember, not rememberSwipeToDismissBoxState (#270): that one is rememberSaveable, and
    // LazyColumn keeps an item's saved state by its key after the item leaves the list. Undo brings
    // the card back with the same localId, so it came back already "dismissed", drawn off-screen,
    // and the effect below deleted it a second time. A card that comes back now starts settled.
    val dismissState = remember(density) {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            density = density,
            confirmValueChange = { value ->
                // Material also settles on "dismissed" for a quick flick of any length; only a drag
                // that really crossed the threshold counts.
                val offset = stateHolder[0]?.let { runCatching { it.requireOffset() }.getOrNull() } ?: 0f
                value != SwipeToDismissBoxValue.EndToStart || abs(offset) >= rowWidth * DELETE_SWIPE_FRACTION
            },
            positionalThreshold = { distance -> distance * DELETE_SWIPE_FRACTION }
        )
    }
    stateHolder[0] = dismissState

    // Deleted once the card has finished sliding off, not the moment the gesture targets it, and
    // only once per swipe even if this effect is restarted while the card is still dismissed.
    var deleteSent by remember { mutableStateOf(false) }
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart && !deleteSent) {
            deleteSent = true
            latestOnDelete()
        }
    }
    val armed = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
    var wasArmed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed != wasArmed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        wasArmed = armed
    }

    val deleteLabel = stringResource(R.string.action_delete)
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        modifier = Modifier
            .onSizeChanged { rowWidth = it.width }
            .semantics {
                customActions = listOf(CustomAccessibilityAction(deleteLabel) { latestOnDelete(); true })
            },
        backgroundContent = {
            // Quiet until letting go would delete, then red with a bigger icon. Both pairs are in
            // ThemePaletteContrastTest (secondary text on a tinted panel; icon on the red swipe).
            val background by animateColorAsState(
                if (armed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant,
                label = "swipeBackground"
            )
            val iconTint by animateColorAsState(
                if (armed) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "swipeIcon"
            )
            val iconScale by animateFloatAsState(if (armed) 1.25f else 0.85f, label = "swipeIconScale")
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background, MaterialTheme.shapes.medium)
                    .padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.scale(iconScale)
                )
            }
        }
    ) {
        content()
    }
}

/** How far across its width a card must be dragged before letting go deletes it (#243). */
private const val DELETE_SWIPE_FRACTION = 0.5f

/** Joins one highlight/note's verse(s) into a single display string from the preloaded
 *  [textByKey] lookup (see [BibleViewModel.libraryVerseText]) — a bare number prefix per verse
 *  only when there's more than one, so a single-verse card (the overwhelming common case) reads
 *  as a clean quotation rather than "16 For God so loved...". */
internal fun resolveVerseText(versionId: String, verses: List<VerseLocationDto>, textByKey: Map<VerseTextKey, String>): String {
    val ordered = verses.sortedWith(compareBy({ it.chapter }, { it.verse }))
    if (ordered.size <= 1) {
        return ordered.firstOrNull()?.let { textByKey[VerseTextKey(versionId, it)] }.orEmpty()
    }
    return ordered.joinToString(" ") { location ->
        val text = textByKey[VerseTextKey(versionId, location)].orEmpty()
        "${location.verse} $text"
    }
}

@Composable
private fun HighlightCard(
    highlight: Highlight,
    verseText: String,
    shownInKjv: Boolean,
    bookName: (Int) -> String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium),
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // A bookmark-tab accent rather than a small legend dot — reads at a glance from
            // across the list, the way a real highlighter streak would down a book's margin.
            // Full opacity, unlike the translucent text-background rendering in BibleText: this
            // is a color swatch, not verse text to keep legible underneath.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(6.dp)
                    .background(Color(highlight.color).copy(alpha = 1f))
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(Spacing.md)
            ) {
                Text(
                    text = verseRefsText(highlight.verses, bookName),
                    style = MaterialTheme.typography.bodyLarge
                )
                if (verseText.isNotBlank()) {
                    Text(
                        text = verseText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                    if (shownInKjv) ShownInKjvLabel(modifier = Modifier.padding(top = Spacing.xs))
                }
                Text(
                    text = displayDate(highlight.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
        }
    }
}

@Composable
private fun NoteCard(
    note: Note,
    verseText: String,
    shownInKjv: Boolean,
    bookName: (Int) -> String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium),
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_stylus_note),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = verseRefsText(note.verses, bookName),
                    style = MaterialTheme.typography.bodyLarge
                )
                if (verseText.isNotBlank()) {
                    Text(
                        text = verseText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                    if (shownInKjv) ShownInKjvLabel(modifier = Modifier.padding(top = Spacing.xs))
                }
                Text(
                    text = note.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.sm)
                )
                Text(
                    text = displayDate(note.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
        }
    }
}

/** Multi-select color filter for the Highlights tab — one toggle per [HighlightColors.palette]
 *  swatch, matched against a highlight's stored (translucent) ARGB via [toHighlightArgb]. */
@Composable
private fun HighlightColorFilterRow(selected: List<Int>, onToggle: (Int) -> Unit, modifier: Modifier = Modifier) {
    // Each swatch sits in a 48dp touch area (#248), so the circles keep their 32dp look with the
    // gaps between them now tappable too. It scrolls sideways rather than squeezing on a narrow
    // screen or at a large font size.
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.library_color_filter),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = Spacing.xs)
        )
        HighlightColors.palette.forEachIndexed { index, swatch ->
            val argb = swatch.toHighlightArgb()
            val name = stringResource(SWATCH_NAMES.getOrElse(index) { R.string.highlight_color_generic })
            val filterLabel = stringResource(R.string.library_show_color_highlights, name)
            val isSelected = argb in selected
            Box(
                modifier = Modifier
                    .size(SWATCH_TOUCH_SIZE)
                    .toggleable(
                        value = isSelected,
                        interactionSource = null,
                        indication = ripple(bounded = false, radius = SWATCH_TOUCH_SIZE / 2),
                        role = Role.Checkbox,
                        onValueChange = { onToggle(argb) }
                    )
                    .semantics { contentDescription = filterLabel },
                contentAlignment = Alignment.Center
            ) {
                HighlightSwatchCircle(swatch, isSelected, 32.dp, ringColor = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun EmptyLibraryState(message: String, icon: Painter) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
