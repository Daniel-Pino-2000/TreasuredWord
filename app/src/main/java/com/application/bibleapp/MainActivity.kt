package com.application.bibleapp

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.animation.PathInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.flow.collectLatest
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.application.bibleapp.components.AboutBookSheet
import com.application.bibleapp.components.AskTopBar
import com.application.bibleapp.components.BibleChapterNav
import com.application.bibleapp.components.BibleTopBar
import com.application.bibleapp.components.BookPickerTopBar
import com.application.bibleapp.components.BookmarkButtonState
import com.application.bibleapp.components.LibraryItemDetailTopBar
import com.application.bibleapp.components.LibraryTopBar
import com.application.bibleapp.components.LoginTopBar
import com.application.bibleapp.components.MainBottomBar
import com.application.bibleapp.components.MainNavigationRail
import com.application.bibleapp.components.RegisterTopBar
import com.application.bibleapp.components.SearchTopBar
import com.application.bibleapp.components.SettingsTopBar
import com.application.bibleapp.components.TopBar
import com.application.bibleapp.components.VersePickerTopBar
import com.application.bibleapp.components.VersionPickerTopBar
import com.application.bibleapp.components.VerseImageSheet
import com.application.bibleapp.verseimage.EXTRA_SHARE_DAILY_VERSE_IMAGE
import com.application.bibleapp.worker.DAILY_VERSE_NOTIFICATION_ID
import com.application.bibleapp.components.VerseSelectionToolbar
import com.application.bibleapp.components.YouTopBar
import com.application.bibleapp.data.model.BibleBooks
import com.application.bibleapp.data.model.BookResources
import com.application.bibleapp.navigation.Navigation
import com.application.bibleapp.navigation.Screen
import com.application.bibleapp.navigation.ScreenTransitions
import com.application.bibleapp.ui.theme.BibleAppTheme
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.ThemeMode
import com.application.bibleapp.ui.theme.isCompactHeight
import com.application.bibleapp.viewmodel.AuthViewModel
import com.application.bibleapp.viewmodel.AuthViewModelFactory
import com.application.bibleapp.viewmodel.AskViewModel
import com.application.bibleapp.viewmodel.AskViewModelFactory
import com.application.bibleapp.viewmodel.BibleViewModel
import com.application.bibleapp.viewmodel.BibleViewModelFactory
import androidx.compose.ui.res.stringResource
import com.application.bibleapp.utils.InAppUpdates
import com.application.bibleapp.utils.UiText
import com.application.bibleapp.utils.isSpanishUi

/** Cumulative scroll distance, in one sustained direction, required before the reading
 *  chrome commits to hiding or showing — see the effect in [MainActivity.onCreate]. */
private const val SCROLL_COMMIT_DP = 120

/** The longest the startup screen waits for Home's content (#161), so a slow disk or a slow
 *  verse-of-the-day fetch never leaves the logo up; Home then fills in as it loads. */
internal const val STARTUP_SCREEN_MAX_WAIT_MS = 1_000L

/** Whether the startup screen stays up [elapsedMs] after launch — see [STARTUP_SCREEN_MAX_WAIT_MS]. */
internal fun keepStartupScreen(contentReady: Boolean, elapsedMs: Long): Boolean =
    !contentReady && elapsedMs < STARTUP_SCREEN_MAX_WAIT_MS

/** How long the logo and name take to fade out over Home once it's ready (#182). Not a delay:
 *  Home is already drawn and usable underneath, this only keeps the logo from vanishing in one frame. */
private const val STARTUP_SCREEN_EXIT_MS = 450L

/** Fades the startup screen out over Home, the logo growing slightly as it goes (#182), then
 *  removes it. With animations off the animators end at once, so it's removed straight away. */
private fun animateStartupScreenExit(provider: SplashScreenViewProvider) {
    val easing = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    // On Android 12+ the icon view is missing when the app wasn't started from the launcher
    // (e.g. from Android Studio), and reading it throws — the background still fades.
    runCatching { provider.iconView }.getOrNull()?.animate()
        ?.alpha(0f)?.scaleX(1.1f)?.scaleY(1.1f)
        ?.setDuration(STARTUP_SCREEN_EXIT_MS)?.setInterpolator(easing)
        ?.start()
    provider.view.animate()
        .alpha(0f)
        .setDuration(STARTUP_SCREEN_EXIT_MS)
        .setInterpolator(easing)
        .withEndAction { provider.remove() }
        .start()
}

/** An AppCompatActivity (not a plain ComponentActivity) so the in-app language applies below
 *  Android 13 too — see [com.application.bibleapp.utils.AppLanguage]. */
class MainActivity : AppCompatActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate: swaps the startup theme (#135) for the app's own.
        val startupScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Fades out over Home once it's ready, rather than vanishing in one frame (#182).
        startupScreen.setOnExitAnimationListener(::animateStartupScreenExit)
        // The same instance setContent's viewModel() returns (same owner, same default key).
        val startupViewModel = ViewModelProvider(this, BibleViewModelFactory(context = this))[BibleViewModel::class.java]
        // Home's first frame would otherwise show Genesis 1 and no highlights for a moment, then
        // jump to the real ones (#161). Checked on every frame until it lets go.
        val launchedAt = SystemClock.uptimeMillis()
        startupScreen.setKeepOnScreenCondition {
            keepStartupScreen(startupViewModel.isStartupContentReady.value, SystemClock.uptimeMillis() - launchedAt)
        }
        // "Share image" from the daily verse notification or widget (#256). Only on a fresh
        // start, not when the activity is recreated (rotation), so it opens once.
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_SHARE_DAILY_VERSE_IMAGE, false) == true) {
            startupViewModel.shareVerseOfTheDayAsImageWhenReady(getString(R.string.verse_of_the_day))
            NotificationManagerCompat.from(this).cancel(DAILY_VERSE_NOTIFICATION_ID)
        }
        // An app left open overnight shows the new day's verse when it comes back (#183).
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) startupViewModel.refreshVerseOfTheDayIfStale()
        })
        // Asks to update when Play has a newer version (#202); registers a result launcher, so here.
        val inAppUpdates = InAppUpdates(this)
        enableEdgeToEdge()
        // Names the channel (shown in the system's notification settings) in the app language.
        createDailyVerseNotificationChannel(this)

        setContent {
            val bibleViewModel: BibleViewModel = viewModel(
                factory = BibleViewModelFactory(context = this)
            )
            val authViewModel: AuthViewModel = viewModel(
                factory = AuthViewModelFactory(context = this)
            )
            // Activity-scoped, so a question keeps waiting for its answer on another tab (#128).
            val askViewModel: AskViewModel = viewModel(
                factory = AskViewModelFactory(context = this)
            )

            val navController = rememberNavController()
            val currentBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = currentBackStackEntry?.destination?.route

            // A slow, smooth settle when the finger lifts (the live 1:1 drag-follow while
            // actively scrolling is intentional platform-standard behavior for an
            // "enterAlways" bar and is left alone — this only governs the snap to fully
            // shown/hidden once the gesture ends).
            val scrollBehavior = if (currentRoute == Screen.Bible.route) {
                // A fresh state whenever the bar's height changes, never a restored one: the
                // default rememberTopAppBarState() kept portrait's hidden offset (-64dp) through a
                // rotation, leaving landscape's 56dp bar -8dp tall, which crashed the app (#175).
                // barsHidden starts fresh after a rotation too, so the two stay in step.
                val topBarState = remember(isCompactHeight()) {
                    TopAppBarState(
                        initialHeightOffsetLimit = -Float.MAX_VALUE,
                        initialHeightOffset = 0f,
                        initialContentOffset = 0f
                    )
                }
                TopAppBarDefaults.enterAlwaysScrollBehavior(
                    state = topBarState,
                    snapAnimationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                )
            } else {
                null
            }

            // Everything else that hides on scroll (bottom tab bar, arrows, both system
            // bars) is driven by this one derived, debounced boolean — deliberately NOT
            // reacting to collapsedFraction, which tracks the top bar's own ~64dp
            // collapse range: 90% of that is only ~57dp, barely more than a single normal
            // scroll swipe, so a fraction-based threshold still felt twitchy in practice.
            // Instead this tracks real cumulative scroll distance via the scroll
            // behavior's unclamped contentOffset, requiring SCROLL_COMMIT_DP of
            // deliberate, sustained scroll in one direction — decoupled from the top
            // bar's short range — before committing to hidden or shown. The anchor slides
            // to the current offset whenever the scroll reverses before reaching the
            // threshold, so a down/up wobble can't "bank" partial progress from one
            // direction and cash it in from the other. Same threshold, same logic, both
            // directions — hiding and revealing feel identically deliberate.
            val density = LocalDensity.current
            var barsHidden by remember { mutableStateOf(false) }
            LaunchedEffect(scrollBehavior) {
                val behavior = scrollBehavior ?: return@LaunchedEffect
                val thresholdPx = with(density) { SCROLL_COMMIT_DP.dp.toPx() }
                var anchor = behavior.state.contentOffset
                snapshotFlow { behavior.state.contentOffset }
                    .collect { offset ->
                        val delta = offset - anchor
                        when {
                            !barsHidden && delta < -thresholdPx -> {
                                barsHidden = true
                                anchor = offset
                            }
                            barsHidden && delta > thresholdPx -> {
                                barsHidden = false
                                anchor = offset
                            }
                            !barsHidden && delta > 0f -> anchor = offset
                            barsHidden && delta < 0f -> anchor = offset
                        }
                    }
            }

            // The actual visual transition for that chrome: one shared, smooth, symmetric
            // animation (not raw scroll-coupled) so hide and reveal feel identical.
            val chromeHiddenFraction by animateFloatAsState(
                targetValue = if (barsHidden) 1f else 0f,
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                label = "chromeHiddenFraction"
            )

            // The system status AND navigation bars hide together with the app's own
            // chrome, driven by the same debounced boolean — previously only the status
            // bar was hidden, which left the system nav bar free to sit on top of (and
            // cover) the book/chapter bar once the tab bar collapsed out from under it.
            LaunchedEffect(scrollBehavior) {
                if (scrollBehavior == null) return@LaunchedEffect
                WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            LaunchedEffect(barsHidden, scrollBehavior) {
                if (scrollBehavior == null) return@LaunchedEffect
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                if (barsHidden) {
                    insetsController.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    insetsController.show(WindowInsetsCompat.Type.systemBars())
                }
            }

            // Guards against leaving the Bible screen mid-scroll with system bars still
            // hidden — every other screen always shows them, and starts fresh (not hidden)
            // if the reader comes back.
            // In landscape the status bar alone is ~12% of the height, so the reader opens
            // immersive there (system bars hidden, swipe to peek); a sustained scroll back up
            // brings them back the same way as in portrait.
            val compactHeightReader = isCompactHeight() && currentRoute == Screen.Bible.route
            LaunchedEffect(compactHeightReader) {
                barsHidden = compactHeightReader
            }
            LaunchedEffect(currentRoute) {
                if (currentRoute != Screen.Bible.route) {
                    barsHidden = false
                    WindowCompat.getInsetsController(window, window.decorView)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
            }

            val themeMode by bibleViewModel.themeMode.collectAsState()
            val themePalette by bibleViewModel.themePalette.collectAsState()
            val useDarkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Android 13+ lets the app pick the next launch's startup screen, so it matches the
            // app's own Light/Dark setting rather than the phone's (#135), in the chosen color
            // theme's background (#244). Below 13 it stays Parchment's, following the phone.
            LaunchedEffect(themeMode, themePalette) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    splashScreen.setSplashScreenTheme(themePalette.splashTheme(themeMode))
                }
            }

            BibleAppTheme(darkTheme = useDarkTheme, palette = themePalette) {
                // The bottom bar's own current height (it animates via chromeHiddenFraction, and
                // differs by route) — read back here so the floating selection toolbar below can
                // always sit a fixed gap above wherever its actual top edge currently is, rather
                // than at a guessed fixed offset that would be wrong the moment the tab row
                // collapses/reappears on scroll.
                var bottomBarHeightPx by remember { mutableIntStateOf(0) }

                // Deletion-undo and copy-confirmation messages from BibleViewModel. collectLatest
                // so a newer message replaces (dismisses) one still showing instead of queueing.
                val snackbarHostState = remember { SnackbarHostState() }
                // Resolved when shown, not when emitted, so a message follows the current language.
                LaunchedEffect(Unit) {
                    bibleViewModel.userMessages.collectLatest { message ->
                        // Don't let an Undo wait behind the update-ready snackbar (#202); that
                        // one comes back the next time the app opens.
                        snackbarHostState.currentSnackbarData?.dismiss()
                        val result = snackbarHostState.showSnackbar(
                            message = message.text.resolve(resources),
                            actionLabel = if (message.undo != null) resources.getString(R.string.action_undo) else null,
                            // Long when there's an Undo — Short's ~4s is too little time to reach it.
                            duration = if (message.undo != null) SnackbarDuration.Long else SnackbarDuration.Short
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            message.undo?.let { bibleViewModel.undoDeletion(it) }
                        }
                    }
                }
                // A new version finished downloading in the background (#202): Restart installs it.
                LaunchedEffect(Unit) {
                    inAppUpdates.updateReady.collect { ready ->
                        if (!ready) return@collect
                        val result = snackbarHostState.showSnackbar(
                            message = resources.getString(R.string.message_update_ready),
                            actionLabel = resources.getString(R.string.action_restart),
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            inAppUpdates.completeUpdate()
                        } else {
                            inAppUpdates.postponeRestart()
                        }
                    }
                }

                // The reader's "About this book" sheet, from the top bar's info icon.
                var aboutBookOpen by rememberSaveable { mutableStateOf(false) }

                // Compact height = a phone in landscape. There the tabs move to a side rail and the
                // chapter controls into the top bar, since a bottom bar plus chapter strip left only
                // ~50dp of a ~360dp-tall screen for actual reading.
                val compactHeight = isCompactHeight()
                val showNavigation = currentRoute !in listOf(
                    Screen.BookPicker.route,
                    Screen.VersePicker.route,
                    Screen.Login.route,
                    Screen.Register.route,
                    Screen.Library.route,
                    Screen.LibraryItemDetail.route,
                    Screen.Settings.route,
                    Screen.LanguagePicker.route,
                    Screen.Licenses.route,
                    Screen.AskConversations.route
                )
                val useRail = compactHeight && showNavigation
                // The rail and the tab bar slide in and out with the page instead of popping, which
                // jumped the page sideways or up while it was still fading (#241). Keyed on the
                // height class so turning the phone swaps them at once rather than animating
                // during the rotation (#238).
                val barSlideSpec = tween<IntSize>(ScreenTransitions.DURATION_MILLIS, easing = FastOutSlowInEasing)
                val barOffsetSpec = tween<IntOffset>(ScreenTransitions.DURATION_MILLIS, easing = FastOutSlowInEasing)
                // While the tabs slide away they keep showing the screen they belonged to, not the
                // one replacing them (the reader's chapter row would otherwise vanish first).
                val tabsRoute = remember { object { var route: String? = null } }
                if (showNavigation) tabsRoute.route = currentRoute
                // The rail sits on the start side: it slides off to the left, or to the right in a
                // right-to-left layout.
                val railSlideSign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1 else -1

                Row(modifier = Modifier.fillMaxSize()) {
                    key(compactHeight) {
                        AnimatedVisibility(
                            visible = useRail,
                            enter = expandHorizontally(barSlideSpec, expandFrom = Alignment.Start) +
                                slideInHorizontally(barOffsetSpec) { railSlideSign * it },
                            exit = shrinkHorizontally(barSlideSpec, shrinkTowards = Alignment.Start) +
                                slideOutHorizontally(barOffsetSpec) { railSlideSign * it }
                        ) {
                            MainNavigationRail(
                                currentRoute = tabsRoute.route,
                                onItemSelected = { route -> navController.navigate(route) }
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            // The rail already pads for a start-side cutout; don't pad again in here.
                            .then(
                                if (useRail) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                                else Modifier
                            )
                    ) {
                        Scaffold(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                                    else Modifier
                                ),
                            topBar = {
                            // Never shorter than the status bar, as when the slot is empty (the
                            // Scaffold then pads by the system bars), so a screen without a bar
                            // (Home) keeps clear of it.
                            Box(Modifier.heightIn(min = WindowInsets.systemBars.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding())) {
                            // The bar changes with the page, on the same timing (#241), instead of
                            // in one frame while the old page is still showing. Its height (and so
                            // the page's top padding) switches the moment the old page has faded
                            // out, before the new one fades in, so neither page visibly jumps
                            // between a screen with a bar and one without (Home). Each bar gets its
                            // own screen's entry, so the one fading out keeps its own title.
                            AnimatedContent(
                                targetState = currentBackStackEntry,
                                contentKey = { it?.destination?.route },
                                transitionSpec = {
                                    (ScreenTransitions.fadeInAfterOut() togetherWith ScreenTransitions.fadeOutFirst())
                                        .using(SizeTransform(clip = false) { _, _ -> snap(delayMillis = ScreenTransitions.FADE_OUT_MILLIS) })
                                },
                                label = "topBar"
                            ) { entry ->
                            when (entry?.destination?.route) {
                                Screen.Bible.route -> {
                                    val versionLabel by bibleViewModel.currentVersionLabel.collectAsState()
                                    val bookmark by bibleViewModel.bookmark.collectAsState()
                                    val bookmarkLabel by bibleViewModel.bookmarkLabel.collectAsState()
                                    val currentBook by bibleViewModel.currentBook.collectAsState()
                                    val currentChapter by bibleViewModel.currentChapter.collectAsState()
                                    BibleTopBar(
                                        scrollBehavior = scrollBehavior,
                                        versionLabel = versionLabel,
                                        onVersionClick = {
                                            bibleViewModel.clearDownloadMessages()
                                            navController.navigate(Screen.VersionPicker.route)
                                        },
                                        bookmark = BookmarkButtonState(
                                            label = bookmarkLabel,
                                            isInThisChapter = bookmark?.let { it.bookId == currentBook && it.chapter == currentChapter } == true,
                                            onBookmarkHere = { bibleViewModel.bookmarkCurrentPosition() },
                                            onGoToBookmark = { bibleViewModel.goToBookmark() },
                                            onRemoveBookmark = { bibleViewModel.removeBookmark() }
                                        ),
                                        onAboutBookClick = if (BookResources.hasAny(currentBook)) {
                                            { aboutBookOpen = true }
                                        } else {
                                            null
                                        },
                                        chapterNav = if (compactHeight) {
                                            {
                                                BibleChapterNav(
                                                    bibleViewModel = bibleViewModel,
                                                    onSelectBook = { navController.navigate(Screen.BookPicker.route) },
                                                    compact = true
                                                )
                                            }
                                        } else {
                                            null
                                        }
                                    )
                                }
                                // No bar: Home's own greeting is its header, and in landscape the bar's
                                // height goes to the Verse of the Day card so it fits unscrolled.
                                Screen.Home.route -> {}
                                Screen.Search.route -> SearchTopBar()
                                Screen.BookPicker.route -> BookPickerTopBar { navController.popBackStack() }
                                Screen.VersePicker.route -> VersePickerTopBar { navController.popBackStack() }
                                Screen.VersionPicker.route -> VersionPickerTopBar { navController.popBackStack() }
                                Screen.You.route -> YouTopBar { navController.navigate(Screen.Settings.route) }
                                Screen.Ask.route -> {
                                    val isLoggedIn by authViewModel.isLoggedIn.collectAsState()
                                    AskTopBar(
                                        showActions = isLoggedIn,
                                        onNewConversation = { askViewModel.newConversation() },
                                        onConversations = { navController.navigate(Screen.AskConversations.route) }
                                    )
                                }
                                Screen.AskConversations.route -> SettingsTopBar(stringResource(Screen.AskConversations.titleRes)) { navController.popBackStack() }
                                Screen.Settings.route -> SettingsTopBar(stringResource(Screen.Settings.titleRes)) { navController.popBackStack() }
                                Screen.LanguagePicker.route -> SettingsTopBar(stringResource(Screen.LanguagePicker.titleRes)) { navController.popBackStack() }
                                Screen.Licenses.route -> SettingsTopBar(stringResource(Screen.Licenses.titleRes)) { navController.popBackStack() }
                                Screen.Login.route -> LoginTopBar { navController.popBackStack() }
                                Screen.Register.route -> RegisterTopBar { navController.popBackStack() }
                                Screen.Library.route -> LibraryTopBar { navController.popBackStack() }
                                Screen.LibraryItemDetail.route -> {
                                    val title = if (entry?.arguments?.getString("type") ==
                                        Screen.LibraryItemDetail.TYPE_NOTE
                                    ) {
                                        stringResource(R.string.title_note)
                                    } else {
                                        stringResource(R.string.title_highlight)
                                    }
                                    LibraryItemDetailTopBar(title) { navController.popBackStack() }
                                }
                                else -> TopBar()
                            }
                            }
                            }
                        },
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        bottomBar = {
                            // Never shorter than the navigation bar, as when the slot is empty (the
                            // Scaffold then pads by the system bars), so the page's bottom edge
                            // doesn't jump once the tab bar has slid away.
                            Box(Modifier.heightIn(min = WindowInsets.systemBars.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding())) {
                                key(compactHeight) {
                                    // Named in full: inside the outer Row, the plain name means
                                    // the Row's own version, which can't be used down here.
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = showNavigation && !compactHeight,
                                        enter = expandVertically(barSlideSpec, expandFrom = Alignment.Top) +
                                            slideInVertically(barOffsetSpec) { it },
                                        exit = shrinkVertically(barSlideSpec, shrinkTowards = Alignment.Top) +
                                            slideOutVertically(barOffsetSpec) { it }
                                    ) {
                                        // Always the ordinary bar, even mid-selection — the selection
                                        // toolbar floats as a separate overlay above it (below), so this
                                        // slot's height never changes just because a selection started or
                                        // ended, which is what caused a reading-list layout jump before.
                                        MainBottomBar(
                                            currentRoute = tabsRoute.route,
                                            bibleViewModel = bibleViewModel,
                                            chromeHiddenFraction = chromeHiddenFraction,
                                            onItemSelected = { route -> navController.navigate(route) },
                                            onBookPickerClicked = { navController.navigate(Screen.BookPicker.route) },
                                            modifier = Modifier.onSizeChanged { bottomBarHeightPx = it.height }
                                        )
                                    }
                                }
                            }
                        }
                        ) { paddingValues ->
                            Navigation(navController, paddingValues, bibleViewModel, authViewModel, askViewModel)
                        }

                        val selectedVerses by bibleViewModel.selectedVerses.collectAsState()
                        val clipboardManager = LocalClipboardManager.current
                        val context = LocalContext.current
                        if (currentRoute == Screen.Bible.route && selectedVerses.isNotEmpty()) {
                            val gap = 12.dp
                            // No bottom bar in compact height (bottomBarHeightPx would be stale from
                            // portrait); sit above the system navigation bar instead.
                            val bottomBarHeight = if (compactHeight) 0.dp else with(LocalDensity.current) { bottomBarHeightPx.toDp() }
                            VerseSelectionToolbar(
                                selectionCount = selectedVerses.size,
                                onColorSelected = { colorArgb -> bibleViewModel.highlightSelection(colorArgb) },
                                onCopy = {
                                    bibleViewModel.takeSelectionShareText()?.let { text ->
                                        clipboardManager.setText(AnnotatedString(text))
                                        // Android 13+ shows its own clipboard confirmation; don't double it.
                                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                            bibleViewModel.showMessage(UiText.Res(R.string.message_copied))
                                        }
                                    }
                                },
                                onShare = {
                                    bibleViewModel.takeSelectionShareText()?.let { text ->
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, text)
                                        }
                                        context.startActivity(Intent.createChooser(send, null))
                                    }
                                },
                                onShareImage = { bibleViewModel.shareSelectionAsImage() },
                                onAddNote = { bibleViewModel.openNoteEditorForSelection() },
                                onCancel = { bibleViewModel.clearSelection() },
                                // "Ask about this verse" (#132): a new Bible Helper conversation with
                                // the verses attached and a question ready to edit.
                                onAsk = if (BuildConfig.AI_HELPER_ENABLED) {
                                    {
                                        bibleViewModel.takeSelectionAiPassage()?.let { passage ->
                                            askViewModel.startWithPassage(passage, resources.getString(R.string.ai_passage_default_question))
                                            navController.navigate(Screen.Ask.route)
                                        }
                                    }
                                } else {
                                    null
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .then(if (compactHeight) Modifier.navigationBarsPadding() else Modifier)
                                    .padding(horizontal = Spacing.lg)
                                    .offset(y = -(bottomBarHeight + gap))
                            )
                        }

                        // The verse image editor (#254), on whatever screen opened it.
                        val verseImageRequest by bibleViewModel.verseImageRequest.collectAsState()
                        verseImageRequest?.let { request ->
                            val verseImageOptions by bibleViewModel.verseImageOptions.collectAsState()
                            VerseImageSheet(
                                request = request,
                                options = verseImageOptions,
                                onOptionsChange = bibleViewModel::updateVerseImageOptions,
                                onShareText = { text ->
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    }
                                    context.startActivity(Intent.createChooser(send, null))
                                },
                                onDismiss = bibleViewModel::closeVerseImage
                            )
                        }

                        if (aboutBookOpen && currentRoute == Screen.Bible.route) {
                            val currentBook by bibleViewModel.currentBook.collectAsState()
                            val bookNames by bibleViewModel.bookNames.collectAsState()
                            AboutBookSheet(
                                bookId = currentBook,
                                bookName = { id -> bookNames[id] ?: BibleBooks.getBookById(id)?.name.orEmpty() },
                                spanish = isSpanishUi(),
                                onDismiss = { aboutBookOpen = false }
                            )
                        }
                    }
                }
            }
        }
    }

    /** Set while [onConfigurationChanged] re-applies the app language, which calls it again. */
    private var reapplyingLanguage = false

    /** Only called for what the manifest's configChanges lists: a language change, which can
     *  also change screenLayout (#89), and turning the phone or plugging in a keyboard (#238).
     *  Compose picks up the new configuration itself; this redoes what lives outside composition
     *  (only the language matters there, and redoing it after a rotation is harmless). */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Below Android 13 the app language is AppCompat's, not the system's: a change the
            // system sends (turning the phone) arrives in the phone's language and resets the
            // resources to it, and AppCompat leaves them that way. Re-apply the app's language;
            // AppCompat updates the resources in place and calls this again with the corrected
            // configuration, which carries on below, so the phone's isn't handed on (#238).
            if (!reapplyingLanguage) {
                reapplyingLanguage = true
                val reapplied = try {
                    delegate.applyDayNight()
                } finally {
                    reapplyingLanguage = false
                }
                if (reapplied) return
            }
            // A change from AppCompat (a language switch) only reaches the activity: hand it to
            // the views too, or Compose keeps showing the old language.
            window.decorView.dispatchConfigurationChanged(newConfig)
        }
        createDailyVerseNotificationChannel(this)
    }
}