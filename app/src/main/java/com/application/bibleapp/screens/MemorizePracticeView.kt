package com.application.bibleapp.screens

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.application.bibleapp.R
import com.application.bibleapp.data.model.BibleTranslation
import com.application.bibleapp.data.model.DEFAULT_VERSION
import com.application.bibleapp.data.model.MAX_MEMORY_LEVEL
import com.application.bibleapp.data.model.MemoryPassage
import com.application.bibleapp.data.model.MemorySort
import com.application.bibleapp.data.model.MemoryStatus
import com.application.bibleapp.data.model.MemoryVerse
import com.application.bibleapp.data.model.PRACTICE_LADDER
import com.application.bibleapp.data.model.PracticeChoice
import com.application.bibleapp.data.model.PracticeGame
import com.application.bibleapp.data.model.PracticeOutcome
import com.application.bibleapp.data.model.PracticePage
import com.application.bibleapp.data.model.REFERENCE_DECOYS
import com.application.bibleapp.data.model.REVIEW_INTERVAL_DAYS
import com.application.bibleapp.data.model.ReferenceQuestion
import com.application.bibleapp.data.model.YouVersionBibles
import com.application.bibleapp.data.model.buildPracticePages
import com.application.bibleapp.data.model.movesOnAfterPass
import com.application.bibleapp.data.model.nextGameAfterPass
import com.application.bibleapp.data.model.passage
import com.application.bibleapp.data.model.referenceDecoys
import com.application.bibleapp.data.model.sortedFor
import com.application.bibleapp.data.remote.VerseLocationDto
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.isCompactHeight
import com.application.bibleapp.utils.FADING_STEPS
import com.application.bibleapp.utils.PracticeWord
import com.application.bibleapp.utils.TypedMark
import com.application.bibleapp.utils.bookNameInVersion
import com.application.bibleapp.utils.checkTypedVerse
import com.application.bibleapp.utils.fadingHiddenCount
import com.application.bibleapp.utils.fadingOrder
import com.application.bibleapp.utils.firstLetterHint
import com.application.bibleapp.utils.joinWords
import com.application.bibleapp.utils.pickGaps
import com.application.bibleapp.utils.practiceWords
import com.application.bibleapp.utils.shuffledOrder
import com.application.bibleapp.utils.splitPhrases
import com.application.bibleapp.utils.tapGamePassed
import com.application.bibleapp.utils.verseRefsText
import com.application.bibleapp.utils.wordBank
import com.application.bibleapp.viewmodel.BibleViewModel
import com.application.bibleapp.viewmodel.VerseTextKey
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Memorize practice (#363): a full-screen feed with one passage per page, swiped up like short
 * videos, and a summary page at the end. Each passage's games sit side by side (#416): it opens
 * on the game for its level, and passing one moves on to the next. All on the device: no AI, no
 * server, so it works offline. Progress goes through [BibleViewModel.recordMemoryPractice] and
 * syncs like any other Memorize change.
 *
 * The pages and their results are kept in saved state, so turning the phone or coming back to
 * the app keeps the feed as it was, even though a passage's level (and so Mixed's game for it)
 * changes as it's played.
 */
@Composable
fun MemorizePracticeView(
    bibleViewModel: BibleViewModel,
    ids: List<Long>,
    game: PracticeGame?,
    modifier: Modifier = Modifier,
    onOpenInBible: (MemoryVerse) -> Unit,
    onDone: () -> Unit
) {
    val loaded by bibleViewModel.libraryLoaded.collectAsState()
    val verses by bibleViewModel.libraryMemoryVerses.collectAsState()
    val textByKey by bibleViewModel.libraryVerseText.collectAsState()
    val bookNames by bibleViewModel.libraryBookNames.collectAsState()
    val missingVersions by bibleViewModel.libraryMissingVersionLabels.collectAsState()
    val catalog by bibleViewModel.availableVersions.collectAsState()
    val streakDays by bibleViewModel.practiceStreakDays.collectAsState()
    LaunchedEffect(Unit) { if (!loaded) bibleViewModel.loadLibrary() }

    val seed = rememberSaveable { Random.nextInt() }
    var round by rememberSaveable { mutableIntStateOf(0) }
    var pageCodes by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var outcomeCodes by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // One "page:GAME" per "Try again" (#416): how many times a game was started over, so its
    // new round keeps its place through a turn of the phone.
    var retryCodes by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // Passages left out because their text isn't on the device (a licensed chapter not read yet, offline).
    var textMissing by rememberSaveable { mutableStateOf(false) }
    val pages = remember(pageCodes) { pageCodes?.map(::decodePage) }
    val outcomes = remember(outcomeCodes) { outcomeCodes.associate(::decodeOutcome) }
    val retries = remember(retryCodes) { retryCodes.groupingBy { it }.eachCount() }

    val textOf = { verse: MemoryVerse -> passageText(verse.versionId, verse.passage, textByKey) }
    LaunchedEffect(loaded, round) {
        if (!loaded || pageCodes != null) return@LaunchedEffect
        val byId = verses.associateBy { it.localId }
        val listed = ids.mapNotNull { byId[it] }
        val chosen = listed.filter { textOf(it).isNotBlank() }
        textMissing = chosen.size < listed.size
        val random = Random(seed + round)
        pageCodes = buildPracticePages(chosen, PracticeChoice(game), random) { verse ->
            referenceDecoys(
                verse.passage, verses.map { it.passage }, chapterVerseCount(verse, textByKey), random
            ) { passageText(verse.versionId, it, textByKey).isNotBlank() }.size == com.application.bibleapp.data.model.REFERENCE_DECOYS
        }.map(::encodePage)
    }

    // Each page pads itself for the keyboard (PracticePageLayout), not the feed: a feed that
    // shrank with the keyboard scrolled on its own to keep the text field in view, onto the next
    // page, on a landscape phone.
    Column(modifier = modifier.fillMaxSize()) {
        val currentPages = pages
        when {
            currentPages == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            currentPages.isEmpty() -> NothingToPractice(onDone, textMissing)
            else -> {
                val pagerState = rememberPagerState { currentPages.size }
                val scope = rememberCoroutineScope()
                LaunchedEffect(round) { if (round > 0) pagerState.scrollToPage(0) }
                val gamePages = currentPages.count { it is PracticePage.Game }
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    key = { "$round-$it" }
                ) { index ->
                    Box(Modifier.fillMaxSize()) {
                        when (val page = currentPages[index]) {
                            PracticePage.Summary -> {
                                // Bonus rounds don't count; a passage played in several games counts once.
                                val played = outcomes.filterKeys { (currentPages[it.page] as? PracticePage.Game)?.bonus == false }
                                PracticeSummary(
                                    practiced = played.keys.map { it.page }.distinct().size,
                                    outcomes = played.values.toList(),
                                    streakDays = streakDays,
                                    onDone = onDone,
                                    onAgain = {
                                        outcomeCodes = emptyList()
                                        retryCodes = emptyList()
                                        pageCodes = null
                                        round++
                                    }
                                )
                            }
                            is PracticePage.Game -> {
                                val verse = verses.firstOrNull { it.localId == page.verseLocalId }
                                if (verse == null) {
                                    // Removed on another device while practicing.
                                    NothingToPractice(onDone)
                                    return@Box
                                }
                                val bookName = { bookId: Int -> bookNameInVersion(verse.versionId, bookId, bookNames) }
                                val roundOf = { g: PracticeGame -> retries["$index:${g.name}"] ?: 0 }
                                val upNext = when (val next = currentPages.getOrNull(index + 1)) {
                                    is PracticePage.Game -> if (next.bonus) UpNext.Bonus else {
                                        val nextVerse = verses.firstOrNull { it.localId == next.verseLocalId }
                                        UpNext.Verse(nextVerse?.let { v -> verseRefsText(v.verses) { bookNameInVersion(v.versionId, it, bookNames) } })
                                    }
                                    else -> UpNext.Finish
                                }
                                PracticePassagePage(
                                    verse = verse,
                                    page = page,
                                    mixed = game == null,
                                    pageNumber = index + 1,
                                    pageCount = gamePages,
                                    upNext = upNext,
                                    text = textOf(verse),
                                    shownInKjv = verse.versionId in missingVersions,
                                    languageCode = languageCodeOf(verse.versionId, verse.versionId in missingVersions, catalog),
                                    seed = seed + round * 7919 + index * 31,
                                    roundOf = roundOf,
                                    outcomeOf = { g -> outcomes[PlayKey(index, g, roundOf(g))] },
                                    isFirst = index == 0,
                                    bookName = bookName,
                                    allPassages = verses.filter { it.versionId == verse.versionId }.map { it.passage },
                                    chapterVerses = chapterVerseCount(verse, textByKey),
                                    passageTextOf = { passageText(verse.versionId, it, textByKey) },
                                    onOpenInBible = { onOpenInBible(verse) },
                                    onFinished = { g, passed ->
                                        val key = PlayKey(index, g, roundOf(g))
                                        if (outcomes[key] == null) {
                                            val outcome = bibleViewModel.recordMemoryPractice(verse.localId, g, passed)
                                            if (outcome != null) {
                                                outcomeCodes = outcomeCodes + encodeOutcome(key, outcome)
                                            }
                                        }
                                    },
                                    onTryAgain = { g -> retryCodes = retryCodes + "$index:${g.name}" },
                                    onNextVerse = { scope.launch { pagerState.animateScrollToPage(index + 1) } }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A finished game's result as the page shows it; [memorizedAfter] tells "still memorized" apart. */
private data class PageOutcome(
    val passed: Boolean,
    val counted: Boolean,
    val levelBefore: Int,
    val levelAfter: Int,
    val newlyMemorized: Boolean,
    val memorizedAfter: Boolean,
    val bonus: Boolean
) {
    val movedUp: Boolean get() = counted && passed && (levelAfter > levelBefore || newlyMemorized)
}

private fun encodePage(page: PracticePage): String = when (page) {
    PracticePage.Summary -> "S"
    is PracticePage.Game -> "G:${page.verseLocalId}:${page.game.name}:${page.bonus}"
}

private fun decodePage(code: String): PracticePage {
    if (code == "S") return PracticePage.Summary
    val (_, id, game, bonus) = code.split(':')
    return PracticePage.Game(id.toLong(), PracticeGame.valueOf(game), bonus.toBoolean())
}

/** What swiping up from a passage leads to, named at the bottom of its page (#416). */
private sealed interface UpNext {
    /** Another passage; [reference] is null if it was just removed on another device. */
    data class Verse(val reference: String?) : UpNext
    data object Bonus : UpNext
    data object Finish : UpNext
}

/** One play of a game: the feed's [page], the [game] on it, and which [round] ("Try again" starts a new one, #416). */
private data class PlayKey(val page: Int, val game: PracticeGame, val round: Int)

private fun encodeOutcome(key: PlayKey, outcome: PracticeOutcome): String = listOf(
    key.page, key.game.name, key.round, outcome.passed, outcome.counted, outcome.levelBefore, outcome.levelAfter,
    outcome.newlyMemorized, outcome.progress?.status == MemoryStatus.MEMORIZED.name, key.game == PracticeGame.WHICH_REFERENCE
).joinToString(":")

private fun decodeOutcome(code: String): Pair<PlayKey, PageOutcome> {
    val p = code.split(':')
    return PlayKey(p[0].toInt(), PracticeGame.valueOf(p[1]), p[2].toInt()) to PageOutcome(
        passed = p[3].toBoolean(), counted = p[4].toBoolean(), levelBefore = p[5].toInt(), levelAfter = p[6].toInt(),
        newlyMemorized = p[7].toBoolean(), memorizedAfter = p[8].toBoolean(), bonus = p[9].toBoolean()
    )
}

/** The passage's verses, without verse numbers (they'd be words to play with). */
private fun passageText(versionId: String, passage: MemoryPassage, textByKey: Map<VerseTextKey, String>): String {
    val texts = (passage.verseStart..passage.verseEnd).map {
        textByKey[VerseTextKey(versionId, VerseLocationDto(passage.bookId, passage.chapter, it))].orEmpty()
    }
    return if (texts.any { it.isBlank() }) "" else texts.joinToString(" ") { it.trim() }
}

/** How many verses the passage's chapter has, as far as the loaded text shows. */
private fun chapterVerseCount(verse: MemoryVerse, textByKey: Map<VerseTextKey, String>): Int =
    textByKey.keys.filter {
        it.versionId == verse.versionId && it.location.bookId == verse.bookId && it.location.chapter == verse.chapter
    }.maxOfOrNull { it.location.verse } ?: verse.verseEnd

/** The passage's language (ISO 639-3) for reading aloud: KJV's when it's shown in KJV (#78). */
private fun languageCodeOf(versionId: String, shownInKjv: Boolean, catalog: List<BibleTranslation>): String? = when {
    shownInKjv || versionId == DEFAULT_VERSION.id -> "eng"
    else -> YouVersionBibles.find(versionId)?.languageCode ?: catalog.firstOrNull { it.id == versionId }?.languageCode
}

@Composable
private fun NothingToPractice(onDone: () -> Unit, textMissing: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(if (textMissing) R.string.practice_text_unavailable else R.string.practice_nothing),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onDone) { Text(stringResource(R.string.practice_done)) }
    }
}

/**
 * One passage of the feed (#416): its header (where it is in the feed, the reference, the level),
 * then its games side by side, easiest first, with tabs naming them. It opens on [page]'s game;
 * a passed game moves on by itself after a moment ([nextGameAfterPass]): in Mixed to the
 * passage's next game, otherwise to the next passage. A bonus round, or a practice of "Which
 * reference?" alone, is a single game with no tabs.
 *
 * Portrait keeps the header above the tabs, since it's the same for every game; a landscape
 * phone has no height for that, so each game shows it on its left side instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PracticePassagePage(
    verse: MemoryVerse,
    page: PracticePage.Game,
    mixed: Boolean,
    pageNumber: Int,
    pageCount: Int,
    upNext: UpNext,
    text: String,
    shownInKjv: Boolean,
    languageCode: String?,
    seed: Int,
    roundOf: (PracticeGame) -> Int,
    outcomeOf: (PracticeGame) -> PageOutcome?,
    isFirst: Boolean,
    bookName: (Int) -> String,
    allPassages: List<MemoryPassage>,
    chapterVerses: Int,
    passageTextOf: (MemoryPassage) -> String,
    onOpenInBible: () -> Unit,
    onFinished: (PracticeGame, Boolean) -> Unit,
    onTryAgain: (PracticeGame) -> Unit,
    onNextVerse: () -> Unit
) {
    val games = if (page.game in PRACTICE_LADDER) PRACTICE_LADDER else listOf(page.game)
    val gamePager = rememberPagerState(initialPage = games.indexOf(page.game)) { games.size }
    val scope = rememberCoroutineScope()
    val compact = isCompactHeight()
    val reference = verseRefsText(verse.verses, bookName)
    val goToGame = { g: PracticeGame -> scope.launch { gamePager.animateScrollToPage(games.indexOf(g)) }; Unit }
    val moveOn = { g: PracticeGame -> nextGameAfterPass(g, mixed)?.let(goToGame) ?: onNextVerse() }

    // The game just passed, to move on from after a moment. Not saved: coming back to a played
    // game, or turning the phone, mustn't move on again.
    var justPassed by remember { mutableStateOf<PracticeGame?>(null) }
    // How far along the wait is, 0..1, shown as a bar in the result so moving on isn't a surprise.
    val moveOnProgress = remember { Animatable(0f) }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(justPassed) {
        moveOnProgress.snapTo(0f)
        val passed = justPassed ?: return@LaunchedEffect
        // Android's recommended time for a screen with buttons: longer when the user has set
        // "Time to take action", and never while TalkBack is on (Long.MAX_VALUE), which needs
        // the time to read the result out.
        val wait = accessibility?.calculateRecommendedTimeoutMillis(
            MOVE_ON_DELAY_MS, containsIcons = true, containsText = true, containsControls = true
        ) ?: MOVE_ON_DELAY_MS
        if (wait == Long.MAX_VALUE) {
            justPassed = null
            return@LaunchedEffect
        }
        moveOnProgress.animateTo(1f, tween(wait.toInt(), easing = LinearEasing))
        justPassed = null
        // Not if they've swiped to another game meanwhile.
        if (games[gamePager.currentPage] == passed) moveOn(passed)
    }

    val gameLabel = when {
        page.bonus -> stringResource(R.string.practice_bonus)
        games.size == 1 -> stringResource(gameNameRes(page.game))
        else -> null
    }
    val header: @Composable ColumnScope.() -> Unit = {
        PracticeHeader(
            verse = verse,
            reference = if (page.game == PracticeGame.WHICH_REFERENCE) null else reference,
            gameLabel = gameLabel,
            pageNumber = pageNumber,
            pageCount = pageCount,
            onOpenInBible = onOpenInBible
        )
        if (shownInKjv) ShownInKjvLabel()
    }
    // The verse's reading size, from the whole passage's length, so it doesn't change size as a
    // game fills it in.
    CompositionLocalProvider(LocalVerseSize provides verseSizeFor(text.length, compact)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!compact) {
                Column(
                    modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    content = header
                )
            }
            val tabs: @Composable (Modifier) -> Unit = { tabsModifier ->
                GameTabs(
                    games = games,
                    selected = gamePager.currentPage,
                    passed = { outcomeOf(it)?.passed == true },
                    onSelect = goToGame,
                    modifier = tabsModifier
                )
            }
            when {
                // In landscape only ~110 dp is left above the keyboard while typing (#228).
                compact && WindowInsets.isImeVisible -> Unit
                // No height to spare for the bottom bar: "up next" is an arrow at the tabs' end.
                compact -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (games.size > 1) tabs(Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                    IconButton(onClick = onNextVerse) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = upNextLabel(upNext),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                games.size > 1 -> tabs(Modifier)
            }
            HorizontalPager(
                state = gamePager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                key = { games[it].name }
            ) { i ->
                val game = games[i]
                val round = roundOf(game)
                val outcome = outcomeOf(game)
                // A new round is a new game: its taps and its gaps start over.
                key(round) {
                    PracticeGamePage(
                        verse = verse,
                        game = game,
                        text = text,
                        reference = reference,
                        languageCode = languageCode,
                        seed = seed + game.ordinal * 13 + round * 101,
                        outcome = outcome,
                        header = if (compact) header else null,
                        // Up and down are named by the bar below; sideways is said once, on the first passage.
                        swipeHint = if (isFirst && games.size > 1) stringResource(R.string.practice_swipe_hint_games) else null,
                        moveOnProgress = if (justPassed == game) moveOnProgress.value else null,
                        allPassages = allPassages,
                        chapterVerses = chapterVerses,
                        passageTextOf = passageTextOf,
                        bookName = bookName,
                        onFinished = { passed ->
                            if (outcome == null) {
                                onFinished(game, passed)
                                if (passed && movesOnAfterPass(game)) justPassed = game
                            }
                        },
                        resultActions = {
                            val nextGame = nextGameAfterPass(game, mixed)
                            if (outcome?.passed == false && game != PracticeGame.WHICH_REFERENCE) {
                                TextButton(onClick = onNextVerse) { Text(stringResource(R.string.practice_next)) }
                                Button(onClick = { onTryAgain(game) }) { Text(stringResource(R.string.practice_try_again)) }
                            } else if (outcome?.passed == true && nextGame != null) {
                                // The arrow points the way the next game comes from: the next tab along.
                                Button(onClick = { justPassed = null; goToGame(nextGame) }) {
                                    Text(stringResource(R.string.practice_next_game))
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.padding(start = Spacing.sm).size(18.dp)
                                    )
                                }
                            } else {
                                Button(onClick = { justPassed = null; onNextVerse() }) { Text(stringResource(R.string.practice_next)) }
                            }
                        }
                    )
                }
            }
            // Not while typing: it would sit behind the keyboard, and the game's own keyboard
            // padding would then count its height twice, pushing Check out of sight.
            if (!compact && !WindowInsets.isImeVisible) UpNextBar(upNext, onNextVerse)
        }
    }
}

/**
 * The bottom of every passage in portrait (#416): what swiping up leads to, with an arrow the way to swipe,
 * so the feed's direction is always on screen rather than learned once from a hint. A tap does
 * the same as the swipe, for anyone who can't or doesn't want to swipe.
 */
@Composable
private fun UpNextBar(upNext: UpNext, onNext: () -> Unit) {
    val label = upNextLabel(upNext)
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = label, onClick = onNext)
                .heightIn(min = 48.dp)
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Two lines, so a long translation keeps the reference, the part worth reading.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}

@Composable
private fun upNextLabel(upNext: UpNext): String = when (upNext) {
    is UpNext.Verse -> upNext.reference?.let { stringResource(R.string.practice_up_next_verse, it) }
        ?: stringResource(R.string.practice_up_next_verse_plain)
    UpNext.Bonus -> stringResource(R.string.practice_up_next_bonus)
    UpNext.Finish -> stringResource(R.string.practice_up_next_finish)
}

/** How long a new result waits, once the keyboard is down, for the page to settle before scrolling into view. */
private const val RESULT_SCROLL_DELAY_MS = 150L

/** How long a passed game's result shows before moving on (#416), before accessibility settings. */
private const val MOVE_ON_DELAY_MS = 1600L

/**
 * The passage's games as tabs (#416), the pattern for pages that swipe sideways: the open one
 * underlined, a check on the ones passed. Scrollable, since some names are long in translation.
 */
@Composable
private fun GameTabs(
    games: List<PracticeGame>,
    selected: Int,
    passed: (PracticeGame) -> Boolean,
    onSelect: (PracticeGame) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    ScrollableTabRow(
        selectedTabIndex = selected,
        modifier = modifier,
        containerColor = colors.background,
        contentColor = colors.primary,
        edgePadding = Spacing.lg,
        divider = { HorizontalDivider(color = colors.outlineVariant) }
    ) {
        games.forEachIndexed { i, game ->
            Tab(
                selected = i == selected,
                onClick = { onSelect(game) },
                selectedContentColor = colors.primary,
                unselectedContentColor = colors.onSurfaceVariant,
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        if (passed(game)) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = stringResource(R.string.practice_game_passed),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(stringResource(gameNameRes(game)), maxLines = 1)
                    }
                }
            )
        }
    }
}

/**
 * One game of a passage. [header] is the passage's header when the game shows it itself
 * (landscape), and [resultActions] the buttons under its result once it's played.
 */
@Composable
private fun PracticeGamePage(
    verse: MemoryVerse,
    game: PracticeGame,
    text: String,
    reference: String,
    languageCode: String?,
    seed: Int,
    outcome: PageOutcome?,
    header: (@Composable ColumnScope.() -> Unit)?,
    swipeHint: String?,
    moveOnProgress: Float?,
    allPassages: List<MemoryPassage>,
    chapterVerses: Int,
    passageTextOf: (MemoryPassage) -> String,
    bookName: (Int) -> String,
    onFinished: (Boolean) -> Unit,
    resultActions: @Composable RowScope.() -> Unit
) {
    val words = remember(text) { practiceWords(text) }
    val view = LocalView.current
    val finish = { passed: Boolean ->
        if (passed) view.confirmHaptic() else view.rejectHaptic()
        onFinished(passed)
    }
    val pageHeader: @Composable ColumnScope.() -> Unit = header ?: {}
    val result: @Composable ColumnScope.() -> Unit = {
        if (outcome != null) {
            PracticeResult(outcome, moveOnProgress, resultActions)
        }
        if (swipeHint != null) {
            Text(
                text = swipeHint,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
    val finished = outcome != null
    when (game) {
        PracticeGame.READ_AND_LISTEN -> ReadAndListen(words, text, languageCode, finished, pageHeader, result, finish)
        PracticeGame.FILL_THE_GAPS -> FillTheGaps(words, seed, finished, pageHeader, result, finish)
        PracticeGame.PUT_IN_ORDER -> PutInOrder(words, seed, finished, pageHeader, result, finish)
        PracticeGame.FIRST_LETTERS -> FirstLetters(words, finished, pageHeader, result, finish)
        PracticeGame.FADING_WORDS -> FadingWords(words, seed, finished, pageHeader, result, finish)
        PracticeGame.TYPE_FROM_MEMORY -> TypeFromMemory(words, finished, pageHeader, result, finish)
        PracticeGame.WHICH_REFERENCE -> WhichReference(
            verse = verse,
            reference = reference,
            text = text,
            seed = seed,
            finished = finished,
            allPassages = allPassages,
            chapterVerses = chapterVerses,
            passageTextOf = passageTextOf,
            bookName = bookName,
            header = pageHeader,
            result = result,
            onFinished = finish
        )
    }
}

/**
 * The game's frame. [verse] is the passage as the game shows it, [controls] what's tapped or
 * typed. [typing] marks the game with a text field, whose left side steps aside in landscape
 * while the keyboard is up.
 *
 * Portrait puts the verse at the top and the controls and result at the bottom, within thumb
 * reach, as quiz apps do (#416): the space between them is what's left over, and both stay put
 * as the game goes on (controls shrink downwards, the verse grows downwards). When it all doesn't
 * fit (a long passage, many phrases), the page scrolls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PracticePageLayout(
    header: @Composable ColumnScope.() -> Unit,
    verse: @Composable ColumnScope.() -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
    result: @Composable ColumnScope.() -> Unit,
    typing: Boolean = false
) {
    val compact = isCompactHeight()
    if (compact) {
        val hideVerse = typing && WindowInsets.isImeVisible
        Row(
            modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(if (hideVerse) 0.dp else Spacing.xl)
        ) {
            // Narrowed to nothing rather than left out, so the text field next to it keeps its
            // place in the composition, and its focus, when the keyboard opens.
            Column(
                modifier = (if (hideVerse) Modifier.width(0.dp) else Modifier.weight(1f))
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                if (!hideVerse) {
                    header()
                    verse()
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                controls()
                result()
            }
        }
    } else {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
            // At least the page's height, so SpaceBetween can push the controls to the bottom;
            // taller when the content is, and then it scrolls.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    header()
                    verse()
                }
                Column(
                    modifier = Modifier.padding(top = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    controls()
                    result()
                }
            }
        }
    }
}

@Composable
private fun PracticeHeader(
    verse: MemoryVerse,
    reference: String?,
    gameLabel: String?,
    pageNumber: Int,
    pageCount: Int,
    onOpenInBible: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = stringResource(R.string.practice_page_of, pageNumber, pageCount) +
                    (gameLabel?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            if (reference != null) {
                Text(
                    text = reference,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() }
                )
            }
            LevelDots(verse)
        }
        // Not on "Which reference?", which would give the answer away.
        if (reference != null) {
            IconButton(onClick = onOpenInBible) {
                Icon(Icons.Outlined.AutoStories, contentDescription = stringResource(R.string.practice_read_in_bible))
            }
        }
    }
}

/** One dot per level, filled up to the passage's; all filled once Memorized. */
@Composable
private fun LevelDots(verse: MemoryVerse) {
    val filled = if (verse.status == MemoryStatus.MEMORIZED) MAX_MEMORY_LEVEL else verse.level.coerceIn(0, MAX_MEMORY_LEVEL)
    val label = if (verse.status == MemoryStatus.MEMORIZED) stringResource(R.string.memorize_status_memorized)
    else stringResource(R.string.practice_level, filled, MAX_MEMORY_LEVEL)
    val colors = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = label }
    ) {
        repeat(MAX_MEMORY_LEVEL) { i ->
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .then(if (i < filled) Modifier.background(colors.primary) else Modifier.border(1.dp, colors.outline, CircleShape))
            )
        }
        if (verse.status == MemoryStatus.MEMORIZED) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.primary,
                modifier = Modifier.padding(start = Spacing.xs)
            )
        }
    }
}

@StringRes
private fun gameNameRes(game: PracticeGame): Int = when (game) {
    PracticeGame.READ_AND_LISTEN -> R.string.practice_game_read
    PracticeGame.FILL_THE_GAPS -> R.string.practice_game_gaps
    PracticeGame.PUT_IN_ORDER -> R.string.practice_game_order
    PracticeGame.FIRST_LETTERS -> R.string.practice_game_letters
    PracticeGame.FADING_WORDS -> R.string.practice_game_fading
    PracticeGame.TYPE_FROM_MEMORY -> R.string.practice_game_type
    PracticeGame.WHICH_REFERENCE -> R.string.practice_game_reference
}

@StringRes
internal fun gameHintRes(game: PracticeGame): Int = when (game) {
    PracticeGame.READ_AND_LISTEN -> R.string.practice_game_read_hint
    PracticeGame.FILL_THE_GAPS -> R.string.practice_game_gaps_hint
    PracticeGame.PUT_IN_ORDER -> R.string.practice_game_order_hint
    PracticeGame.FIRST_LETTERS -> R.string.practice_game_letters_hint
    PracticeGame.FADING_WORDS -> R.string.practice_game_fading_hint
    PracticeGame.TYPE_FROM_MEMORY -> R.string.practice_game_type_hint
    PracticeGame.WHICH_REFERENCE -> R.string.practice_game_reference_hint
}

internal fun practiceGameName(game: PracticeGame): Int = gameNameRes(game)

/** How large a game shows its passage (#416): as large as it comfortably fits, by its length. */
internal enum class VerseSize { LARGE, MEDIUM, SMALL }

/** Up to about four lines at the largest size on a phone held upright. */
internal const val SHORT_PASSAGE_CHARS = 140

/** Up to about ten lines at the middle size. */
internal const val MEDIUM_PASSAGE_CHARS = 320

/**
 * The size for a passage of [length] characters. A landscape phone ([compactHeight]) has half
 * the width for the verse and little height, so it stays a step smaller.
 */
internal fun verseSizeFor(length: Int, compactHeight: Boolean): VerseSize = when {
    length <= SHORT_PASSAGE_CHARS -> if (compactHeight) VerseSize.MEDIUM else VerseSize.LARGE
    length <= MEDIUM_PASSAGE_CHARS && !compactHeight -> VerseSize.MEDIUM
    else -> VerseSize.SMALL
}

private val LocalVerseSize = staticCompositionLocalOf { VerseSize.SMALL }

/** The passage in the reading face, as the reader shows Scripture, at the passage's [VerseSize]. */
@Composable
private fun VerseText(text: AnnotatedString, modifier: Modifier = Modifier) {
    val typography = MaterialTheme.typography
    val style = when (LocalVerseSize.current) {
        VerseSize.LARGE -> typography.headlineMedium
        VerseSize.MEDIUM -> typography.headlineSmall
        VerseSize.SMALL -> typography.titleLarge
    }
    Text(
        text = text,
        style = style.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal),
        modifier = modifier
    )
}

@Composable
private fun VerseText(text: String) = VerseText(AnnotatedString(text))

@Composable
private fun Instructions(@StringRes res: Int) {
    Text(
        text = stringResource(res),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Said out loud by TalkBack when it changes ("Not that one"), and shown under the verse. */
@Composable
private fun Feedback(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

/** "Got it" or "Not yet", for the games where you check yourself. */
@Composable
private fun SelfCheck(onResult: (Boolean) -> Unit) {
    Text(stringResource(R.string.practice_check_yourself), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Button(onClick = { onResult(true) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.practice_got_it))
        }
        OutlinedButton(onClick = { onResult(false) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.practice_not_yet))
        }
    }
}

/** A blank's stand-in: as wide as the word, roughly, without giving its length away exactly. */
private fun blankFor(word: PracticeWord): String = if (word.isCharacter) "＿" else "_".repeat(word.core.length.coerceIn(3, 8))

// ---- 1. Read and listen (level 0) ----

@Composable
private fun ReadAndListen(
    words: List<PracticeWord>,
    text: String,
    languageCode: String?,
    finished: Boolean,
    header: @Composable ColumnScope.() -> Unit,
    result: @Composable ColumnScope.() -> Unit,
    onFinished: (Boolean) -> Unit
) {
    val speaker = rememberVerseSpeaker(languageCode)
    PracticePageLayout(
        header = header,
        verse = { VerseText(words.joinWords()) },
        controls = {
            if (speaker?.available == true) {
                OutlinedButton(onClick = { if (speaker.speaking) speaker.stop() else speaker.speak(text) }) {
                    Icon(
                        if (speaker.speaking) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.padding(end = Spacing.sm)
                    )
                    Text(stringResource(if (speaker.speaking) R.string.practice_stop else R.string.practice_listen))
                }
            }
            if (!finished) {
                Button(onClick = { speaker?.stop(); onFinished(true) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.practice_read_done))
                }
            }
        },
        result = result
    )
}

/**
 * Android's own text-to-speech (#363), free and on the device: only a voice that needs no
 * network is used, so the verse never leaves the phone. Null while there's no language to read
 * in; [VerseSpeaker.available] stays false when the phone has no such voice, and the Listen
 * button doesn't show.
 */
@Composable
private fun rememberVerseSpeaker(languageCode: String?): VerseSpeaker? {
    val context = LocalContext.current
    val locale = remember(languageCode) { ttsLocaleFor(languageCode) } ?: return null
    val speaker = remember(locale) { VerseSpeaker(context, locale) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    return speaker
}

@Stable
private class VerseSpeaker(context: Context, private val locale: Locale) {
    var available by mutableStateOf(false)
        private set
    var speaking by mutableStateOf(false)
        private set
    private var shutDown = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS && !shutDown) setUp()
    }

    private fun setUp() {
        val voice = runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { v: Voice ->
                v.locale.language == locale.language && !v.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features
            }
            .sortedWith(compareByDescending<Voice> { it.locale.country == locale.country }.thenByDescending { it.quality })
            .firstOrNull() ?: return
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) return
        tts.setSpeechRate(0.9f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { speaking = true }
            override fun onDone(utteranceId: String?) { speaking = false }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { speaking = false }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { speaking = false }
        })
        available = true
    }

    fun speak(text: String) {
        if (!available) return
        speaking = true
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun stop() {
        if (available) tts.stop()
        speaking = false
    }

    fun shutdown() {
        shutDown = true
        tts.stop()
        tts.shutdown()
    }

    private companion object {
        const val UTTERANCE_ID = "memorize_verse"
    }
}

/** A catalog language code (ISO 639-3, like "spa") as a Locale for text-to-speech. */
internal fun ttsLocaleFor(languageCode: String?): Locale? {
    val code = languageCode?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
    val language = when (code) {
        "cmn", "zho", "chi" -> "zh"
        "tgl", "fil" -> "fil"
        else -> if (code.length == 2) code
        else Locale.getISOLanguages().firstOrNull { runCatching { Locale(it).isO3Language }.getOrNull() == code } ?: return null
    }
    return Locale(language)
}

// ---- 2. Fill the gaps (level 1) ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FillTheGaps(
    words: List<PracticeWord>,
    seed: Int,
    finished: Boolean,
    header: @Composable ColumnScope.() -> Unit,
    result: @Composable ColumnScope.() -> Unit,
    onFinished: (Boolean) -> Unit
) {
    val gaps = remember(words, seed) { pickGaps(words, Random(seed)) }
    val bank = remember(words, gaps, seed) { wordBank(words, gaps, Random(seed + 1)) }
    var filled by rememberSaveable { mutableIntStateOf(0) }
    var firstTryRight by rememberSaveable { mutableIntStateOf(0) }
    var missedCurrent by rememberSaveable { mutableStateOf(false) }
    var used by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    var wrongTap by remember { mutableStateOf<Pair<Int, Int>?>(null) } // bank position, count
    val view = LocalView.current
    val colors = MaterialTheme.colorScheme
    val blank = stringResource(R.string.practice_gap_blank)
    val notThatOne = stringResource(R.string.practice_not_that_one)
    val done = finished || filled >= gaps.size

    val shown = remember(words, gaps, filled, done, colors) {
        buildAnnotatedString {
            words.forEachIndexed { i, word ->
                if (i > 0 && word.spaceBefore) append(' ')
                val gap = gaps.indexOf(i)
                when {
                    gap < 0 || done -> if (gap >= 0) {
                        append(word.leading)
                        withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.SemiBold)) { append(word.core) }
                        append(word.trailing)
                    } else append(word.text)
                    gap < filled -> {
                        append(word.leading)
                        withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.SemiBold)) { append(word.core) }
                        append(word.trailing)
                    }
                    gap == filled -> {
                        append(word.leading)
                        withStyle(SpanStyle(color = colors.onPrimaryContainer, background = colors.primaryContainer)) { append(blankFor(word)) }
                        append(word.trailing)
                    }
                    else -> append(word.leading + blankFor(word) + word.trailing)
                }
            }
        }
    }
    val spoken = remember(words, gaps, filled, done, blank) {
        words.indices.joinToString(" ") { i ->
            val gap = gaps.indexOf(i)
            if (gap >= 0 && !done && gap >= filled) blank else words[i].text
        }
    }
    PracticePageLayout(
        header = header,
        verse = {
            VerseText(shown, Modifier.semantics { contentDescription = spoken })
            if (!done && wrongTap != null) Feedback(notThatOne)
        },
        controls = {
            if (!done) {
                Instructions(R.string.practice_gaps_instructions)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    bank.forEachIndexed { position, wordIndex ->
                        if (position in used) return@forEachIndexed
                        val shake = wrongTap?.takeIf { it.first == position }?.second ?: 0
                        AssistChip(
                            onClick = {
                                val expected = words[gaps[filled]].key
                                if (words[wordIndex].key == expected) {
                                    used = used + position
                                    val right = if (missedCurrent) firstTryRight else firstTryRight + 1
                                    firstTryRight = right
                                    missedCurrent = false
                                    wrongTap = null
                                    filled++
                                    if (filled >= gaps.size) onFinished(tapGamePassed(right, gaps.size))
                                    else view.tickHaptic()
                                } else {
                                    missedCurrent = true
                                    wrongTap = position to ((wrongTap?.second ?: 0) + 1)
                                    view.rejectHaptic()
                                }
                            },
                            label = { Text(words[wordIndex].core, style = MaterialTheme.typography.titleMedium) },
                            modifier = Modifier.shakeOn(shake)
                        )
                    }
                }
            }
        },
        result = result
    )
}

// ---- 3. Put it in order (level 2) ----

@Composable
private fun PutInOrder(
    words: List<PracticeWord>,
    seed: Int,
    finished: Boolean,
    header: @Composable ColumnScope.() -> Unit,
    result: @Composable ColumnScope.() -> Unit,
    onFinished: (Boolean) -> Unit
) {
    val phrases = remember(words) { splitPhrases(words) }
    val phraseText = remember(words, phrases) { phrases.map { words.subList(it.first, it.last + 1).joinWords() } }
    val phraseKey = remember(words, phrases) { phrases.map { r -> words.subList(r.first, r.last + 1).joinToString("") { it.key } } }
    val order = remember(phrases, seed) { shuffledOrder(phrases.size, Random(seed)) }
    var placed by rememberSaveable { mutableIntStateOf(0) }
    var firstTryRight by rememberSaveable { mutableIntStateOf(0) }
    var missedCurrent by rememberSaveable { mutableStateOf(false) }
    var used by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    var wrongTap by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val view = LocalView.current
    val notThatOne = stringResource(R.string.practice_not_that_one)
    val done = finished || placed >= phrases.size

    PracticePageLayout(
        header = header,
        verse = {
            if (done) {
                VerseText(words.joinWords())
            } else {
                val soFar = phraseText.take(placed).joinToString(if (words.any { it.spaceBefore }) " " else "")
                VerseText(if (soFar.isEmpty()) "…" else "$soFar …")
                if (wrongTap != null) Feedback(notThatOne)
            }
        },
        controls = {
            if (!done) {
                Instructions(R.string.practice_order_instructions)
                order.forEachIndexed { position, phrase ->
                    if (position in used) return@forEachIndexed
                    val shake = wrongTap?.takeIf { it.first == position }?.second ?: 0
                    OutlinedButton(
                        onClick = {
                            // Two phrases can read the same ("Holy, holy,"): either one is right.
                            if (phraseKey[phrase] == phraseKey[placed]) {
                                used = used + position
                                val right = if (missedCurrent) firstTryRight else firstTryRight + 1
                                firstTryRight = right
                                missedCurrent = false
                                wrongTap = null
                                placed++
                                if (placed >= phrases.size) onFinished(tapGamePassed(right, phrases.size))
                                else view.tickHaptic()
                            } else {
                                missedCurrent = true
                                wrongTap = position to ((wrongTap?.second ?: 0) + 1)
                                view.rejectHaptic()
                            }
                        },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().shakeOn(shake)
                    ) {
                        Text(
                            text = phraseText[phrase],
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        result = result
    )
}

// ---- 4. First letters (level 3) ----

@Composable
private fun FirstLetters(
    words: List<PracticeWord>,
    finished: Boolean,
    header: @Composable ColumnScope.() -> Unit,
    result: @Composable ColumnScope.() -> Unit,
    onFinished: (Boolean) -> Unit
) {
    var revealed by rememberSaveable { mutableIntStateOf(0) }
    val colors = MaterialTheme.colorScheme
    val all = finished || revealed >= words.size
    val shown = remember(words, revealed, all, colors) {
        buildAnnotatedString {
            words.forEachIndexed { i, word ->
                if (i > 0 && word.spaceBefore) append(' ')
                if (all || i < revealed) append(word.text)
                else withStyle(SpanStyle(color = colors.primary)) { append(firstLetterHint(words, i)) }
            }
        }
    }
    PracticePageLayout(
        header = header,
        verse = { VerseText(shown) },
        controls = {
            if (!finished) {
                Instructions(R.string.practice_letters_instructions)
                if (!all) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        OutlinedButton(onClick = { revealed++ }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.practice_letters_next_word))
                        }
                        TextButton(onClick = { revealed = words.size }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.practice_show_all))
                        }
                    }
                } else {
                    SelfCheck(onFinished)
                }
            }
        },
        result = result
    )
}

// ---- 5. Fading words (level 4) ----

@Composable
private fun FadingWords(
    words: List<PracticeWord>,
    seed: Int,
    finished: Boolean,
    header: @Composable ColumnScope.() -> Unit,
    result: @Composable ColumnScope.() -> Unit,
    onFinished: (Boolean) -> Unit
) {
    val order = remember(words, seed) { fadingOrder(words, Random(seed)) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var shownAgain by rememberSaveable { mutableStateOf(false) }
    val reveal = finished || shownAgain
    val hidden = remember(order, step) { order.take(fadingHiddenCount(order.size, step)).toSet() }
    val blank = stringResource(R.string.practice_gap_blank)
    val text = if (reveal) words.joinWords() else buildString {
        words.forEachIndexed { i, w ->
            if (i > 0 && w.spaceBefore) append(' ')
            append(if (i in hidden) w.leading + blankFor(w) + w.trailing else w.text)
        }
    }
    val spoken = if (reveal) text else words.withIndex().joinToString(" ") { (i, w) -> if (i in hidden) blank else w.text }
    PracticePageLayout(
        header = header,
        verse = { VerseText(AnnotatedString(text), Modifier.semantics { contentDescription = spoken }) },
        controls = {
            if (!finished) {
                when {
                    step < FADING_STEPS -> {
                        Instructions(R.string.practice_fading_instructions)
                        Button(onClick = { step++ }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.practice_fading_hide))
                        }
                    }
                    !shownAgain -> {
                        Instructions(R.string.practice_fading_recite)
                        Button(onClick = { shownAgain = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.practice_fading_show))
                        }
                    }
                    else -> SelfCheck(onFinished)
                }
            }
        },
        result = result
    )
}

// ---- 6. Type it from memory (level 5, the proof) ----

@Composable
private fun TypeFromMemory(
    words: List<PracticeWord>,
    finished: Boolean,
    header: @Composable ColumnScope.() -> Unit,
    result: @Composable ColumnScope.() -> Unit,
    onFinished: (Boolean) -> Unit
) {
    var typed by rememberSaveable { mutableStateOf("") }
    var checked by rememberSaveable { mutableStateOf(false) }
    val check = remember(words, typed, checked) { if (checked) checkTypedVerse(words, typed) else null }
    val colors = MaterialTheme.colorScheme
    val missed = stringResource(R.string.practice_mark_missed)
    val wrong = stringResource(R.string.practice_mark_wrong)
    val typo = stringResource(R.string.practice_mark_typo)
    PracticePageLayout(
        header = header,
        typing = true,
        verse = {
            if (check == null) {
                Instructions(R.string.practice_type_instructions)
            } else {
                // Each word of the passage, marked: what was missed or wrong in red, a forgiven
                // typo underlined. TalkBack hears the mark after the word.
                val playable = words.indices.filter { words[it].key.isNotEmpty() }
                val markOf = playable.zip(check.marks).toMap()
                val shown = buildAnnotatedString {
                    words.forEachIndexed { i, word ->
                        if (i > 0 && word.spaceBefore) append(' ')
                        when (markOf[i]) {
                            TypedMark.MISSED, TypedMark.WRONG -> withStyle(SpanStyle(color = colors.error, fontWeight = FontWeight.Bold)) { append(word.text) }
                            TypedMark.TYPO -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(word.text) }
                            else -> append(word.text)
                        }
                    }
                }
                val spoken = words.withIndex().joinToString(" ") { (i, word) ->
                    when (markOf[i]) {
                        TypedMark.MISSED -> "${word.text} ($missed)"
                        TypedMark.WRONG -> "${word.text} ($wrong)"
                        TypedMark.TYPO -> "${word.text} ($typo)"
                        else -> word.text
                    }
                }
                Text(
                    text = stringResource(R.string.practice_type_score, (check.score * 100).toInt()),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (check.passed) colors.primary else colors.error
                )
                VerseText(shown, Modifier.semantics { contentDescription = spoken })
                Instructions(R.string.practice_type_legend)
            }
        },
        controls = {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text(stringResource(R.string.practice_type_label)) },
                enabled = !checked && !finished,
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                // Room for the label above the border when the field is scrolled to the top of
                // what the keyboard leaves.
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
            )
            if (!checked && !finished) {
                Button(
                    onClick = {
                        checked = true
                        onFinished(checkTypedVerse(words, typed).passed)
                    },
                    enabled = typed.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.practice_type_check)) }
            }
        },
        result = result
    )
}

// ---- 7. Which reference? (a bonus round) ----

@Composable
private fun WhichReference(
    verse: MemoryVerse,
    reference: String,
    text: String,
    seed: Int,
    finished: Boolean,
    allPassages: List<MemoryPassage>,
    chapterVerses: Int,
    passageTextOf: (MemoryPassage) -> String,
    bookName: (Int) -> String,
    header: @Composable ColumnScope.() -> Unit,
    result: @Composable ColumnScope.() -> Unit,
    onFinished: (Boolean) -> Unit
) {
    val target = verse.passage
    val question = remember(seed) { if (Random(seed).nextBoolean()) ReferenceQuestion.PICK_REFERENCE else ReferenceQuestion.PICK_TEXT }
    val choices = remember(seed, allPassages, chapterVerses) {
        val random = Random(seed + 2)
        (referenceDecoys(target, allPassages, chapterVerses, random) { passageTextOf(it).isNotBlank() } + target).shuffled(random)
    }
    var chosen by rememberSaveable { mutableIntStateOf(-1) }
    val colors = MaterialTheme.colorScheme
    PracticePageLayout(
        header = header,
        verse = {
            if (question == ReferenceQuestion.PICK_REFERENCE) {
                Text(stringResource(R.string.practice_reference_pick_ref), style = MaterialTheme.typography.titleMedium)
                VerseText(practiceWords(text).joinWords())
            } else {
                Text(
                    stringResource(R.string.practice_reference_pick_text, reference),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() }
                )
            }
        },
        controls = {
            choices.forEachIndexed { i, passage ->
                val isTarget = passage == target
                val answered = chosen >= 0 || finished
                val (container, content) = when {
                    answered && isTarget -> colors.primaryContainer to colors.onPrimaryContainer
                    answered && i == chosen -> colors.errorContainer to colors.onErrorContainer
                    else -> colors.surface to colors.onSurface
                }
                val label = if (question == ReferenceQuestion.PICK_REFERENCE) {
                    verseRefsText((passage.verseStart..passage.verseEnd).map { VerseLocationDto(passage.bookId, passage.chapter, it) }, bookName)
                } else {
                    passageTextOf(passage)
                }
                Surface(
                    onClick = {
                        if (!answered) {
                            chosen = i
                            onFinished(isTarget)
                        }
                    },
                    enabled = !answered,
                    color = container,
                    contentColor = content,
                    shape = MaterialTheme.shapes.medium,
                    border = if (answered && (isTarget || i == chosen)) null
                    else androidx.compose.foundation.BorderStroke(1.dp, colors.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = label,
                            style = if (question == ReferenceQuestion.PICK_REFERENCE) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (answered && (isTarget || i == chosen)) {
                            Icon(if (isTarget) Icons.Filled.Check else Icons.Filled.Close, contentDescription = null)
                        }
                    }
                }
            }
        },
        result = result
    )
}

// ---- Result, summary, and the feel of it ----

/**
 * What the game did, with a check that pops in on a pass, and [actions] (Next, Try again).
 * [moveOnProgress] fills a bar while the page is about to move on by itself (#416).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun PracticeResult(outcome: PageOutcome, moveOnProgress: Float?, actions: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when {
        outcome.passed -> colors.primaryContainer to colors.onPrimaryContainer
        outcome.counted -> colors.errorContainer to colors.onErrorContainer
        else -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    val message = when {
        outcome.bonus -> stringResource(if (outcome.passed) R.string.practice_right else R.string.practice_answer_marked)
        !outcome.counted -> stringResource(if (outcome.passed) R.string.practice_result_extra_pass else R.string.practice_result_extra_fail)
        !outcome.passed -> stringResource(R.string.practice_result_down)
        outcome.newlyMemorized -> stringResource(R.string.practice_result_memorized)
        outcome.memorizedAfter -> stringResource(R.string.practice_result_still_memorized)
        outcome.levelBefore == MAX_MEMORY_LEVEL -> stringResource(R.string.practice_result_one_more)
        else -> {
            val days = REVIEW_INTERVAL_DAYS[outcome.levelBefore]
            pluralStringResource(R.plurals.practice_result_level, days, outcome.levelAfter, MAX_MEMORY_LEVEL, days)
        }
    }
    val movingOn = stringResource(R.string.practice_moving_on)
    var popped by rememberSaveable { mutableStateOf(false) }
    val inView = remember { BringIntoViewRequester() }
    val keyboardUp by rememberUpdatedState(WindowInsets.isImeVisible)
    LaunchedEffect(Unit) {
        if (popped) return@LaunchedEffect
        popped = true
        // A new result scrolls into sight with its buttons (#416): under a long passage, or once
        // Type it from memory's keyboard has gone down, it can land below the fold. Only once
        // the keyboard is down and the page has settled at its new height.
        snapshotFlow { keyboardUp }.first { !it }
        delay(RESULT_SCROLL_DELAY_MS)
        inView.bringIntoView()
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(inView).shakeOn(if (!outcome.passed) 1 else 0)
    ) {
        Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                val icon: ImageVector = if (outcome.passed) Icons.Filled.Check else Icons.Filled.Close
                AnimatedVisibility(
                    visible = popped,
                    enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn()
                ) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(if (outcome.passed) colors.primary else content),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = if (outcome.passed) colors.onPrimary else container)
                    }
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            if (moveOnProgress != null) {
                LinearProgressIndicator(
                    progress = { moveOnProgress },
                    color = colors.primary,
                    trackColor = colors.outlineVariant,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = movingOn }
                )
            }
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}

@Composable
private fun PracticeSummary(
    practiced: Int,
    outcomes: List<PageOutcome>,
    streakDays: Int,
    onDone: () -> Unit,
    onAgain: () -> Unit
) {
    val compact = isCompactHeight()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(if (compact) Spacing.md else Spacing.lg, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.practice_summary_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }
        )
        val movedUp = outcomes.count { it.movedUp }
        val memorized = outcomes.count { it.newlyMemorized }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(pluralStringResource(R.plurals.practice_summary_practiced, practiced, practiced), style = MaterialTheme.typography.titleMedium)
            Text(pluralStringResource(R.plurals.practice_summary_moved_up, movedUp, movedUp), style = MaterialTheme.typography.bodyLarge)
            if (memorized > 0) {
                Text(
                    pluralStringResource(R.plurals.practice_summary_memorized, memorized, memorized),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (streakDays > 0) StreakLabel(streakDays)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            OutlinedButton(onClick = onAgain) { Text(stringResource(R.string.practice_again)) }
            Button(onClick = onDone) { Text(stringResource(R.string.practice_done)) }
        }
    }
}

/** "3 days in a row", on the summary and the Memorize tab (#363). */
@Composable
internal fun StreakLabel(days: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Icon(Icons.Outlined.LocalFireDepartment, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(
            text = pluralStringResource(R.plurals.practice_streak, days, days),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** A short side-to-side shake each time [trigger] goes up: a wrong tap. */
@Composable
private fun Modifier.shakeOn(trigger: Int): Modifier {
    val offset = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger <= 0) return@LaunchedEffect
        for (x in listOf(10f, -10f, 6f, -6f, 3f, 0f)) offset.animateTo(x, tween(45))
    }
    return this.offset { IntOffset(offset.value.dp.roundToPx(), 0) }
}

/** A light tick on a right tap, a confirm on a passed game, a buzz on a wrong one. */
private fun View.tickHaptic() {
    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}

private fun View.confirmHaptic() {
    performHapticFeedback(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
}

private fun View.rejectHaptic() {
    performHapticFeedback(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
}

/** The Memorize tab's passages in practice order: what most needs it first. */
internal fun practiceOrder(verses: List<MemoryVerse>): List<MemoryVerse> = verses.sortedFor(MemorySort.DUE)
