package com.application.bibleapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.application.bibleapp.components.BibleTopBar
import com.application.bibleapp.components.BookPickerTopBar
import com.application.bibleapp.components.HomeTopBar
import com.application.bibleapp.components.LibraryTopBar
import com.application.bibleapp.components.LoginTopBar
import com.application.bibleapp.components.MainBottomBar
import com.application.bibleapp.components.RegisterTopBar
import com.application.bibleapp.components.SearchTopBar
import com.application.bibleapp.components.SettingsTopBar
import com.application.bibleapp.components.TopBar
import com.application.bibleapp.components.VersePickerTopBar
import com.application.bibleapp.components.VersionPickerTopBar
import com.application.bibleapp.navigation.Navigation
import com.application.bibleapp.navigation.Screen
import com.application.bibleapp.ui.theme.BibleAppTheme
import com.application.bibleapp.ui.theme.ThemeMode
import com.application.bibleapp.viewmodel.AuthViewModel
import com.application.bibleapp.viewmodel.AuthViewModelFactory
import com.application.bibleapp.viewmodel.BibleViewModel
import com.application.bibleapp.viewmodel.BibleViewModelFactory

/** Cumulative scroll distance, in one sustained direction, required before the reading
 *  chrome commits to hiding or showing — see the effect in [MainActivity.onCreate]. */
private const val SCROLL_COMMIT_DP = 120

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val bibleViewModel: BibleViewModel = viewModel(
                factory = BibleViewModelFactory(context = this)
            )
            val authViewModel: AuthViewModel = viewModel(
                factory = AuthViewModelFactory(context = this)
            )

            val navController = rememberNavController()
            val currentBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = currentBackStackEntry?.destination?.route

            // A slow, smooth settle when the finger lifts (the live 1:1 drag-follow while
            // actively scrolling is intentional platform-standard behavior for an
            // "enterAlways" bar and is left alone — this only governs the snap to fully
            // shown/hidden once the gesture ends).
            val scrollBehavior = if (currentRoute == Screen.Bible.route) {
                TopAppBarDefaults.enterAlwaysScrollBehavior(
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
            LaunchedEffect(currentRoute) {
                if (currentRoute != Screen.Bible.route) {
                    barsHidden = false
                    WindowCompat.getInsetsController(window, window.decorView)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
            }

            val themeMode by bibleViewModel.themeMode.collectAsState()
            val useDarkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            BibleAppTheme(darkTheme = useDarkTheme) {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                            else Modifier
                        ),
                    topBar = {
                        when (currentRoute) {
                            Screen.Bible.route -> {
                                val versionLabel by bibleViewModel.currentVersionLabel.collectAsState()
                                BibleTopBar(
                                    scrollBehavior = scrollBehavior,
                                    versionLabel = versionLabel,
                                    onVersionClick = { navController.navigate(Screen.VersionPicker.route) }
                                )
                            }
                            Screen.Home.route -> HomeTopBar()
                            Screen.Search.route -> SearchTopBar()
                            Screen.BookPicker.route -> BookPickerTopBar { navController.popBackStack() }
                            Screen.VersePicker.route -> VersePickerTopBar { navController.popBackStack() }
                            Screen.VersionPicker.route -> VersionPickerTopBar { navController.popBackStack() }
                            Screen.More.route -> SettingsTopBar()
                            Screen.Login.route -> LoginTopBar { navController.popBackStack() }
                            Screen.Register.route -> RegisterTopBar { navController.popBackStack() }
                            Screen.Library.route -> LibraryTopBar { navController.popBackStack() }
                            else -> TopBar()
                        }
                    },
                    bottomBar = {
                        if (currentRoute !in listOf(
                                Screen.BookPicker.route,
                                Screen.VersePicker.route,
                                Screen.Login.route,
                                Screen.Register.route,
                                Screen.Library.route
                            )
                        ) {
                            MainBottomBar(
                                currentRoute = currentRoute,
                                bibleViewModel = bibleViewModel,
                                hideBar = false,
                                chromeHiddenFraction = chromeHiddenFraction,
                                onItemSelected = { route -> navController.navigate(route) },
                                onBookPickerClicked = { navController.navigate(Screen.BookPicker.route) }
                            )
                        }
                    }
                ) { paddingValues ->
                    Navigation(navController, paddingValues, bibleViewModel, authViewModel)
                }
            }
        }
    }
}