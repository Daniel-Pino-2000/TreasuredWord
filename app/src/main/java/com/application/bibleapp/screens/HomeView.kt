package com.application.bibleapp.screens

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil3.compose.AsyncImage
import com.application.bibleapp.R
import com.application.bibleapp.components.BibleProjectLogo
import com.application.bibleapp.components.FitOnOneLineText
import com.application.bibleapp.components.GotQuestionsLogo
import com.application.bibleapp.components.MissingVersionBanners
import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.data.model.BookResources
import com.application.bibleapp.data.model.Highlight
import com.application.bibleapp.data.model.MAX_MEMORY_LEVEL
import com.application.bibleapp.data.model.MemoryStatus
import com.application.bibleapp.data.model.MemoryVerse
import com.application.bibleapp.data.model.chapterCount
import com.application.bibleapp.ui.theme.MaxContentWidth
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.VideoThumbnailColors
import com.application.bibleapp.ui.theme.centeredContentWidth
import com.application.bibleapp.ui.theme.highlightFill
import com.application.bibleapp.ui.theme.isCompactHeight
import com.application.bibleapp.utils.NetworkUtils
import com.application.bibleapp.utils.UiText
import com.application.bibleapp.utils.bookNameInVersion
import com.application.bibleapp.utils.isSpanishUi
import com.application.bibleapp.utils.parseIsoTimestamp
import com.application.bibleapp.utils.rememberExternalLinks
import com.application.bibleapp.utils.rememberIsOnline
import com.application.bibleapp.utils.verseRefsText
import com.application.bibleapp.verseimage.VerseImageContent
import com.application.bibleapp.verseimage.VerseImageFormat
import com.application.bibleapp.verseimage.VerseImageOptions
import com.application.bibleapp.verseimage.VerseImageRenderer
import com.application.bibleapp.viewmodel.BibleViewModel
import com.application.bibleapp.widget.firstLettersHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/** How many of the newest highlights the Home strip shows before "See all". */
private const val RECENT_HIGHLIGHT_COUNT = 5

/**
 * Home (#365): each card has its own color role, so the screen isn't one flat tint. The Verse of
 * the Day is the image Share image makes; under it, Continue Reading and today's verse to
 * memorize; then "Go deeper" with BibleProject's overview video and GotQuestions.org for the
 * book being read; then recent highlights.
 */
@Composable
fun HomeView(
    bibleViewModel: BibleViewModel,
    modifier: Modifier = Modifier,
    onContinueReadingClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    onHighlightClick: (Highlight) -> Unit,
    onVerseOfTheDayClick: () -> Unit,
    onSeeAllHighlightsClick: () -> Unit,
    onMemoryVerseClick: (MemoryVerse) -> Unit
) {
    // The recent-highlights strip and the memorize tile read the Library's data; reload on every
    // visit so a change made in the reader shows up here straight away.
    LaunchedEffect(Unit) { bibleViewModel.loadLibrary() }

    val verseOfTheDay by bibleViewModel.verseOfTheDay.collectAsState()
    val versionLabel by bibleViewModel.currentVersionLabel.collectAsState()
    val imageOptions by bibleViewModel.verseImageOptions.collectAsState()
    val currentBook by bibleViewModel.currentBook.collectAsState()
    val currentBookName by bibleViewModel.currentBookName.collectAsState()
    val currentChapter by bibleViewModel.currentChapter.collectAsState()
    val bookNames by bibleViewModel.bookNames.collectAsState()
    val chapterStructure by bibleViewModel.chapterStructure.collectAsState()
    val bookmarkLabel by bibleViewModel.bookmarkLabel.collectAsState()
    val highlights by bibleViewModel.libraryHighlights.collectAsState()
    val memoryVerse by bibleViewModel.homeMemoryVerse.collectAsState()
    val verseTextByKey by bibleViewModel.libraryVerseText.collectAsState()
    val bookNamesByVersion by bibleViewModel.libraryBookNames.collectAsState()
    val isRestoring by bibleViewModel.isRestoringContent.collectAsState()

    // ISO-8601 UTC timestamps sort chronologically as plain strings.
    val recentHighlights = remember(highlights) {
        highlights.sortedByDescending { it.createdAt }.take(RECENT_HIGHLIGHT_COUNT)
    }
    // Each card in its own translation's book names, like its text (#54).
    val bookName = { versionId: String, bookId: Int -> bookNameInVersion(versionId, bookId, bookNamesByVersion) }
    // The reader's translation, for the Go deeper section's book names.
    val readerBookName = { bookId: Int -> bookNames[bookId] ?: BibleBooks.getBookById(bookId)?.name.orEmpty() }

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val spanish = isSpanishUi()
    val externalLinks = rememberExternalLinks { bibleViewModel.showMessage(it) }
    val compactHeight = isCompactHeight()

    // The image's label (#256), in the app language like the rest of Home.
    val verseOfTheDayLabel = stringResource(R.string.verse_of_the_day)
    val imageContent = remember(verseOfTheDay, versionLabel, verseOfTheDayLabel) {
        bibleViewModel.verseOfTheDayImageContent(verseOfTheDayLabel)
    }

    val greeting: @Composable () -> Unit = { HomeGreeting() }
    // [imageSide]: landscape's fixed size for the image; portrait fills the width.
    val verseCard: @Composable (imageSide: Dp?) -> Unit = { imageSide ->
        val verse = verseOfTheDay
        if (verse != null && imageContent != null) {
            VerseOfTheDayImageCard(
                content = imageContent,
                options = imageOptions,
                imageSide = imageSide,
                description = "$verseOfTheDayLabel. ${verse.text} ${verse.reference}",
                onOpen = onVerseOfTheDayClick,
                onCopy = {
                    bibleViewModel.verseOfTheDayShareText()?.let { text ->
                        clipboardManager.setText(AnnotatedString(text))
                        // Android 13+ shows its own clipboard confirmation; don't double it.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            bibleViewModel.showMessage(UiText.Res(R.string.message_copied))
                        }
                    }
                },
                onShareText = {
                    bibleViewModel.verseOfTheDayShareText()?.let { text ->
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }
                },
                onShareImage = { bibleViewModel.shareVerseOfTheDayAsImage(verseOfTheDayLabel) }
            )
        }
    }
    val tiles: @Composable () -> Unit = {
        val verse = memoryVerse
        // Side by side, the same height; Continue Reading alone fills the row with an empty list.
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            ContinueReadingTile(
                reference = "$currentBookName $currentChapter",
                chapter = currentChapter,
                chapterCount = chapterStructure.chapterCount(currentBook),
                wide = verse == null,
                onClick = onContinueReadingClick,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            if (verse != null) {
                MemorizeTile(
                    verse = verse,
                    reference = verseRefsText(verse.verses) { bookName(verse.versionId, it) },
                    text = resolveVerseText(verse.versionId, verse.verses, verseTextByKey),
                    onClick = { onMemoryVerseClick(verse) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
        bookmarkLabel?.let { label ->
            HomeShortcutRow(
                icon = Icons.Filled.Bookmark,
                title = stringResource(R.string.label_bookmark),
                subtitle = label,
                onClick = onBookmarkClick
            )
        }
    }
    val goDeeper: @Composable () -> Unit = {
        Text(
            text = stringResource(R.string.home_go_deeper, currentBookName),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .padding(top = Spacing.xs)
                .semantics { heading() }
        )
        val videoId = BookResources.overviewVideos(currentBook, spanish).firstOrNull()
        if (videoId != null) {
            // 1–2 Kings share one video (and 1–2 Samuel and Chronicles in Spanish): name both books.
            val covered = BookResources.booksCovered(videoId, currentBook, spanish)
            val title = if (covered.first != covered.last) {
                "${readerBookName(covered.first)}–${readerBookName(covered.last)}"
            } else {
                currentBookName
            }
            val more = remember(currentBook, spanish) { BookResources.nextOverviews(currentBook, spanish) }
            val moreBooks = if (compactHeight) emptyList() else more.map { (book, id) -> readerBookName(book) to id }
            // A video about the chapter being read leads the card (#407); the book's overview
            // then opens the small row, ahead of the next books'.
            val passage = remember(currentBook, currentChapter, spanish) {
                BookResources.passageVideos(currentBook, currentChapter, spanish).firstOrNull()
            }
            if (passage != null) {
                val reference = passage.reference(currentBookName)
                WatchCard(
                    videoId = passage.videoId,
                    heading = stringResource(R.string.home_watch_passage_title, reference),
                    playLabel = stringResource(R.string.home_watch_passage_play, reference),
                    more = if (compactHeight) emptyList() else listOf(title to videoId) + moreBooks.take(2),
                    moreLabel = stringResource(R.string.home_watch_more_videos),
                    horizontal = compactHeight,
                    spanish = spanish,
                    onPlay = { externalLinks.openYouTubeVideo(it) },
                    onCreditClick = { externalLinks.openPage(BookResources.bibleProjectHome(spanish)) }
                )
            } else {
                WatchCard(
                    videoId = videoId,
                    heading = stringResource(R.string.home_watch_title, title),
                    playLabel = stringResource(R.string.home_watch_play, title),
                    more = moreBooks,
                    moreLabel = stringResource(R.string.home_watch_more),
                    horizontal = compactHeight,
                    spanish = spanish,
                    onPlay = { externalLinks.openYouTubeVideo(it) },
                    onCreditClick = { externalLinks.openPage(BookResources.bibleProjectHome(spanish)) }
                )
            }
        }
        // GotQuestions.org (#47): its book summaries are English pages; the front page has a Spanish site.
        QuestionsCard(
            bookName = currentBookName,
            onSummaryClick = BookResources.summaryUrl(currentBook)?.let { url -> { externalLinks.openPage(url) } },
            onBrowseClick = { externalLinks.openPage(BookResources.gotQuestionsHome(spanish)) }
        )
    }
    val highlightsSection: @Composable () -> Unit = {
        RecentHighlightsSection(
            highlights = recentHighlights,
            isRestoring = isRestoring,
            verseText = { highlight -> resolveVerseText(highlight.versionId, highlight.verses, verseTextByKey) },
            bookName = bookName,
            onHighlightClick = onHighlightClick,
            onSeeAllClick = onSeeAllHighlightsClick
        )
        // Recent highlights and the memorize tile in a translation that isn't downloaded show KJV text (#78).
        MissingVersionBanners(
            bibleViewModel,
            versionIds = recentHighlights.mapTo(mutableSetOf()) { it.versionId } + listOfNotNull(memoryVerse?.versionId)
        )
    }

    if (compactHeight) {
        // Landscape: the verse image on the left, sized from the height Home really has so it and
        // its buttons fit on the first screen; everything else in a column on the right.
        BoxWithConstraints(modifier = modifier) {
            val imageSide = (maxHeight - Spacing.lg * 2 - LandscapeVerseActionsHeight)
                .coerceIn(MinLandscapeImage, MaxLandscapeImage)
            Row(
                modifier = Modifier
                    .centeredContentWidth(maxWidth = MaxContentWidth * 1.4f)
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
            ) {
                // At least wide enough for the buttons under the image on one line; the image is centered.
                Column(modifier = Modifier.width(maxOf(imageSide, LandscapeVerseColumnMinWidth))) {
                    verseCard(imageSide)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    greeting()
                    tiles()
                    goDeeper()
                    highlightsSection()
                }
            }
        }
    } else {
        Column(
            modifier = modifier
                .centeredContentWidth()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            greeting()
            verseCard(null)
            tiles()
            goDeeper()
            highlightsSection()
        }
    }
}

/** Landscape's verse image: Home's height less its padding and the image's row of buttons. */
private val LandscapeVerseActionsHeight = 48.dp
private val MinLandscapeImage = 160.dp
private val MaxLandscapeImage = 340.dp
private val LandscapeVerseColumnMinWidth = 280.dp

/** Home's greeting for an hour of the day (0–23): morning 5–11, afternoon 12–17, else evening. */
internal fun homeGreetingRes(hour: Int): Int = when (hour) {
    in 5..11 -> R.string.home_greeting_morning
    in 12..17 -> R.string.home_greeting_afternoon
    else -> R.string.home_greeting_evening
}

/** "Good morning" (afternoon / evening by the clock) over today's date in the app's language. */
@Composable
private fun HomeGreeting() {
    val locale = LocalConfiguration.current.locales[0]
    // Both lines come from one "now" that's taken again whenever the app comes back to the
    // foreground: Home stays composed while the app is in the background, so a plain remember
    // kept yesterday's date and the morning's greeting overnight (#188). No ticking while it's
    // on screen; a greeting a few minutes late is fine.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) now = System.currentTimeMillis()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val greetingRes = remember(now) {
        homeGreetingRes(Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY))
    }
    // A sunrise, sun or moon to match the greeting (#365). Decorative: the words say it.
    val icon = when (greetingRes) {
        R.string.home_greeting_morning -> Icons.Outlined.WbTwilight
        R.string.home_greeting_afternoon -> Icons.Outlined.LightMode
        else -> Icons.Outlined.NightsStay
    }
    val date = remember(locale, now) {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")
        SimpleDateFormat(pattern, locale).format(Date(now))
    }
    Row(
        modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(greetingRes),
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = date,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Today's verse drawn as the very image Share image makes (#365): VerseImageRenderer, square,
 * in the image style saved in the editor (the theme's own until one is picked), so Home shows
 * what will be sent. Its colors are the image's, in light and dark mode alike, like a photo;
 * a light image on a dark Home gets a hairline so its edge shows. TalkBack reads [description].
 */
@Composable
private fun VerseOfTheDayImageCard(
    content: VerseImageContent,
    options: VerseImageOptions,
    imageSide: Dp?,
    description: String,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onShareText: () -> Unit,
    onShareImage: () -> Unit
) {
    val style = options.style
    val styleBackground = Color(style.background)
    val edge = if (isSystemInDarkTheme() && styleBackground.luminance() > 0.5f) {
        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
    } else {
        Modifier
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(
            modifier = (if (imageSide != null) Modifier.size(imageSide) else Modifier.fillMaxWidth().aspectRatio(1f))
                .clip(MaterialTheme.shapes.large)
                .background(styleBackground)
                .then(edge)
                .clickable(onClickLabel = stringResource(R.string.verse_of_the_day_open), onClick = onOpen)
                .clearAndSetSemantics { contentDescription = description }
        ) {
            val side = constraints.maxWidth
            val renderOptions = options.copy(format = VerseImageFormat.SQUARE, showLabel = true)
            // Drawn off the main thread at the card's own pixel size; the style's background shows meanwhile.
            val bitmap by produceState<ImageBitmap?>(null, content, renderOptions, side) {
                value = withContext(Dispatchers.Default) {
                    VerseImageRenderer.render(content, renderOptions, side, side).asImageBitmap()
                }
            }
            bitmap?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCopy) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.action_copy),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onShareText) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = stringResource(R.string.share_as_text),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Right-aligned in the rest of the row, on one line, shrinking in a long translation
            // rather than becoming a two-line pill (#365).
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Button(onClick = onShareImage, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                    FitOnOneLineText(
                        text = stringResource(R.string.daily_verse_share_image),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/** Where you left off, with how far into the book that chapter is. [wide] when it has the row to itself. */
@Composable
private fun ContinueReadingTile(
    reference: String,
    chapter: Int,
    chapterCount: Int,
    wide: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label: @Composable () -> Unit = {
        Text(
            text = stringResource(R.string.home_continue_reading),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = reference,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                if (wide) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = Spacing.md)
                    ) { label() }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (!wide) Column { label() }
            Spacer(modifier = Modifier.weight(1f, fill = false))
            if (chapterCount > 1) {
                LinearProgressIndicator(
                    progress = { (chapter.toFloat() / chapterCount).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    // A decorative track, so outlineVariant; `outline` is for control borders (#244).
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
                Text(
                    text = stringResource(R.string.home_chapter_progress, chapter, chapterCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Today's passage from the Memorize list (#362), picked by the widget's rules (#364): its
 * reference, the first-letter hint, five dots for its level and a Due badge once practice (#363)
 * has scheduled a review that's come round. In the theme's second accent (#365).
 */
@Composable
private fun MemorizeTile(
    verse: MemoryVerse,
    reference: String,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filled = if (verse.status == MemoryStatus.MEMORIZED) MAX_MEMORY_LEVEL else verse.level.coerceIn(0, MAX_MEMORY_LEVEL)
    // Only a review date practice has set; a passage that's never been practiced isn't flagged.
    val due = verse.nextReviewAt?.let(::parseIsoTimestamp)?.let { it <= System.currentTimeMillis() } == true
    val status = when (verse.status) {
        MemoryStatus.NEW -> stringResource(R.string.memorize_status_new)
        MemoryStatus.LEARNING -> stringResource(R.string.memorize_status_learning, verse.level, MAX_MEMORY_LEVEL)
        MemoryStatus.MEMORIZED -> stringResource(R.string.memorize_status_memorized)
    }
    val title = stringResource(R.string.widget_memorize_title)
    val dueLabel = stringResource(R.string.home_memorize_due)
    // The first-letter hint means nothing read aloud, so TalkBack hears the passage's name and progress.
    val description = listOfNotNull(title, reference, status, dueLabel.takeIf { due }).joinToString(". ")
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .padding(Spacing.md)
                .clearAndSetSemantics { contentDescription = description },
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(text = title, style = MaterialTheme.typography.labelMedium)
            }
            Text(
                text = reference,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = firstLettersHint(text),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.padding(top = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    repeat(MAX_MEMORY_LEVEL) { index ->
                        val dot = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                        Box(
                            modifier = if (index < filled) {
                                dot.background(MaterialTheme.colorScheme.tertiary)
                            } else {
                                dot.border(1.5.dp, MaterialTheme.colorScheme.tertiary, CircleShape)
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (due) {
                    Text(
                        text = dueLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.tertiary, CircleShape)
                            .padding(horizontal = Spacing.sm, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/** The source logos at the top of the "Go deeper" cards (#405). */
private val HomeLogoHeight = 22.dp

/**
 * BibleProject's video for the chapter being read (#407), or else their overview of the book
 * (#55, #365), with YouTube's own thumbnail and the credit their terms ask for, then [more]
 * videos under [moreLabel]. [horizontal] puts the picture beside the text, for landscape.
 */
@Composable
private fun WatchCard(
    videoId: String,
    heading: String,
    playLabel: String,
    more: List<Pair<String, String>>,
    moreLabel: String,
    horizontal: Boolean,
    spanish: Boolean,
    onPlay: (String) -> Unit,
    onCreditClick: () -> Unit
) {
    val context = LocalContext.current
    // Offline or with Data Saver on, the tile shows instead and nothing is fetched.
    val online = rememberIsOnline()
    val loadThumbnails = online && remember(online) { !NetworkUtils.isDataSaverOn(context) }
    val headingBlock: @Composable (Modifier) -> Unit = { textModifier ->
        Column(modifier = textModifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (spanish) {
                // Proyecto Biblia's own logo isn't offered for download: name it instead (#405).
                Text(
                    text = stringResource(R.string.source_bibleproject),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                BibleProjectLogo(
                    background = MaterialTheme.colorScheme.surfaceContainerHighest,
                    height = HomeLogoHeight,
                    modifier = Modifier.padding(vertical = Spacing.xs)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = stringResource(R.string.external_link_opens_outside),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
    // BibleProject's terms: a clear notice naming them as author and owner, linking to their site.
    val credit: @Composable (Modifier) -> Unit = { creditModifier ->
        Text(
            text = stringResource(R.string.about_book_video_credit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = creditModifier
                .fillMaxWidth()
                .clickable(onClick = onCreditClick)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
        )
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        if (horizontal) {
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                VideoThumbnail(
                    videoId = videoId,
                    large = true,
                    load = loadThumbnails,
                    playSize = 40.dp,
                    modifier = Modifier
                        .weight(0.45f)
                        .aspectRatio(16f / 9f)
                        .clickable(onClickLabel = playLabel) { onPlay(videoId) }
                )
                Column(modifier = Modifier.weight(0.55f), verticalArrangement = Arrangement.Center) {
                    headingBlock(
                        Modifier
                            .clickable(onClickLabel = playLabel) { onPlay(videoId) }
                            .padding(start = Spacing.md, end = Spacing.md, top = Spacing.sm)
                    )
                    credit(Modifier)
                }
            }
        } else {
            Column {
                Column(modifier = Modifier.clickable(onClickLabel = playLabel) { onPlay(videoId) }) {
                    VideoThumbnail(
                        videoId = videoId,
                        large = true,
                        load = loadThumbnails,
                        playSize = 56.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                    )
                    headingBlock(Modifier.padding(start = Spacing.md, end = Spacing.md, top = Spacing.md))
                }
                credit(Modifier)
                if (more.isNotEmpty()) {
                    Text(
                        text = moreLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.md)
                    )
                    Row(
                        modifier = Modifier.padding(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = Spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        more.forEach { (bookName, id) ->
                            val label = stringResource(R.string.home_watch_play, bookName)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable(onClickLabel = label) { onPlay(id) },
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                            ) {
                                VideoThumbnail(
                                    videoId = id,
                                    large = false,
                                    load = loadThumbnails,
                                    playSize = 24.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .clip(MaterialTheme.shapes.small)
                                )
                                Text(
                                    text = bookName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A video's picture: YouTube's own thumbnail, shown whole and unedited (#365), over a tile in the
 * theme's two accents that shows while it loads, offline, with Data Saver on, or if it fails.
 * The play button is centered on whichever is showing.
 */
@Composable
private fun VideoThumbnail(videoId: String, large: Boolean, load: Boolean, playSize: Dp, modifier: Modifier = Modifier) {
    var loaded by remember(videoId, load) { mutableStateOf(false) }
    val tile = Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.primaryContainer)
    )
    Box(modifier = modifier.background(tile), contentAlignment = Alignment.Center) {
        if (load) {
            AsyncImage(
                model = BookResources.videoThumbnailUrl(videoId, large),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .size(playSize)
                .background(if (loaded) VideoThumbnailColors.PlayDisc else MaterialTheme.colorScheme.tertiary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = if (loaded) VideoThumbnailColors.PlayIcon else MaterialTheme.colorScheme.onTertiary,
                modifier = Modifier.size(playSize * 0.6f)
            )
        }
    }
}

/**
 * GotQuestions.org for the book being read (#47, #365): its summary of the book, when there's a
 * checked one, and the site's questions. Someone else's site, so the card names it and each
 * button shows the opens-outside icon.
 */
@Composable
private fun QuestionsCard(bookName: String, onSummaryClick: (() -> Unit)?, onBrowseClick: () -> Unit) {
    val opensOutside = stringResource(R.string.external_link_opens_outside)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // Their logo names the card (#405); its speech bubbles stand in for the old "?" tile.
            GotQuestionsLogo(
                background = MaterialTheme.colorScheme.primaryContainer,
                height = HomeLogoHeight,
                modifier = Modifier.padding(vertical = Spacing.xs)
            )
            Column {
                Text(
                    text = stringResource(R.string.home_questions_book_title, bookName),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.home_questions_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            // Side by side when both fit (landscape, tablets); otherwise one under the other, full
            // width, rather than a ragged wrap. 340 dp fits both labels in English at default size.
            BoxWithConstraints {
                val sideBySide = maxWidth >= 340.dp
                val buttonModifier = if (sideBySide) Modifier else Modifier.fillMaxWidth()
                val buttons: @Composable (Modifier) -> Unit = { weightModifier ->
                    if (onSummaryClick != null) {
                        Button(
                            onClick = onSummaryClick,
                            modifier = weightModifier.then(buttonModifier),
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding
                        ) {
                            Text(stringResource(R.string.about_book_summary))
                            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = opensOutside,
                                modifier = Modifier.size(ButtonDefaults.IconSize)
                            )
                        }
                    }
                    // Bordered in primary: `outline` is too faint on the card's tint (#365).
                    OutlinedButton(
                        onClick = onBrowseClick,
                        modifier = weightModifier.then(buttonModifier),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding
                    ) {
                        Text(stringResource(R.string.home_questions_browse))
                        Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = opensOutside,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                    }
                }
                if (sideBySide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) { buttons(Modifier) }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) { buttons(Modifier) }
                }
            }
        }
    }
}

/** The newest few highlights as a sideways strip, or a hint about highlighting when there are none. */
@Composable
private fun RecentHighlightsSection(
    highlights: List<Highlight>,
    isRestoring: Boolean,
    verseText: (Highlight) -> String,
    bookName: (versionId: String, bookId: Int) -> String,
    onHighlightClick: (Highlight) -> Unit,
    onSeeAllClick: () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.home_recent_highlights),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
            if (highlights.isNotEmpty()) {
                TextButton(onClick = onSeeAllClick) {
                    Text(stringResource(R.string.home_see_all))
                }
            }
        }
        if (highlights.isEmpty() && isRestoring) {
            // The first sync after signing in, e.g. on a reinstall (#79): the highlights are on
            // their way, and appear here by themselves once they're in.
            Row(
                modifier = Modifier.padding(top = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.sync_restoring_content),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (highlights.isEmpty()) {
            Text(
                text = stringResource(R.string.home_highlights_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs)
            )
        } else {
            // Keyed cards keep the current first card in view when the list changes, so a new
            // highlight, inserted at index 0, landed off-screen to the left (#58). Show it instead —
            // only when the newest one actually changes, so coming back to Home otherwise keeps
            // wherever the row was scrolled to.
            val rowState = rememberLazyListState()
            val newestId = highlights.first().localId
            var shownNewestId by rememberSaveable { mutableStateOf(newestId) }
            LaunchedEffect(newestId) {
                if (newestId != shownNewestId) {
                    shownNewestId = newestId
                    rowState.scrollToItem(0)
                }
            }
            LazyRow(
                state = rowState,
                modifier = Modifier.padding(top = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                contentPadding = PaddingValues(end = Spacing.sm)
            ) {
                items(highlights, key = { it.localId }) { highlight ->
                    RecentHighlightCard(
                        reference = verseRefsText(highlight.verses) { bookName(highlight.versionId, it) },
                        text = verseText(highlight),
                        color = highlightFill(highlight.color),
                        onClick = { onHighlightClick(highlight) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentHighlightCard(reference: String, text: String, color: Color, onClick: () -> Unit) {
    // highlightFill is translucent (weaker in dark mode, #246), so over `surface` it reads as the
    // same tint the verse has in the reader, in light and dark alike.
    Surface(
        modifier = Modifier.width(176.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .background(color)
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // Always three lines tall, so every card in the strip matches without a fixed height
            // that taller scripts' line spacing (e.g. Korean) would overflow.
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                minLines = 3,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = reference,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HomeShortcutRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailingIcon: ImageVector = Icons.Filled.ChevronRight,
    trailingIconDescription: String? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                // onSurface, not the Surface's muted onSurfaceVariant, to match the tiles above.
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = trailingIcon,
                contentDescription = trailingIconDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
