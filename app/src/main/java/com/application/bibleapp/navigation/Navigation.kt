package com.application.bibleapp.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.application.bibleapp.data.repository.BibleRepository
import com.application.bibleapp.screens.BibleView
import com.application.bibleapp.screens.BookPickerView
import com.application.bibleapp.screens.HomeView
import com.application.bibleapp.screens.LibraryItemDetailView
import com.application.bibleapp.screens.LibraryView
import com.application.bibleapp.screens.LoginView
import com.application.bibleapp.screens.RegisterView
import com.application.bibleapp.screens.SearchView
import com.application.bibleapp.screens.VersePickerView
import com.application.bibleapp.screens.VersionPickerView
import com.application.bibleapp.screens.YouView
import com.application.bibleapp.screens.settings.SettingsView
import com.application.bibleapp.ui.theme.centeredContentWidth
import com.application.bibleapp.viewmodel.AuthViewModel
import com.application.bibleapp.viewmodel.BibleViewModel
import com.application.bibleapp.worker.SyncScheduler
import com.application.bibleapp.screens.settings.LanguagePickerView
import com.application.bibleapp.screens.settings.LicensesView

@Composable
fun Navigation(
    navController: NavHostController,
    padding: PaddingValues,
    bibleViewModel: BibleViewModel,
    authViewModel: AuthViewModel
) {
    val context = LocalContext.current
    // Every screen but the reader (which caps its own reading column) is laid out in a centered,
    // max-width column, so nothing stretches edge to edge in landscape.
    val screenModifier = Modifier.padding(padding).centeredContentWidth()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {

        composable(Screen.Home.route) {
            HomeView(
                bibleViewModel = bibleViewModel,
                // Home picks its own max width — wider in landscape, where it goes two-column.
                modifier = Modifier.padding(padding),
                onContinueReadingClick = { navController.navigate(Screen.Bible.route) },
                onBookmarkClick = {
                    bibleViewModel.goToBookmark()
                    navController.navigate(Screen.Bible.route)
                },
                onHighlightClick = { highlight ->
                    bibleViewModel.openHighlightFromLibrary(highlight)
                    navController.navigate(Screen.Bible.route)
                },
                onSeeAllHighlightsClick = {
                    navController.navigate(Screen.Library.createRoute(Screen.Library.TAB_HIGHLIGHTS))
                }
            )
        }

        composable(Screen.Bible.route) {
            BibleView(
                bibleViewModel,
                modifier = Modifier.padding(padding),
                onSignInClick = { navController.navigate(Screen.Login.route) }
            )
        }

        composable(Screen.Search.route) {
            // The search is kept (it lives in the ViewModel), so Back returns to the same results.
            SearchView(bibleViewModel, modifier = screenModifier) { bookId, chapter, verse ->
                bibleViewModel.setBook(bookId, chapter, verse)
                navController.navigate(Screen.Bible.route)
            }
        }


        composable(Screen.You.route) {
            YouView(
                bibleViewModel = bibleViewModel,
                authViewModel = authViewModel,
                modifier = screenModifier,
                onSignInClick = { navController.navigate(Screen.Login.route) },
                onLibraryClick = { tab -> navController.navigate(Screen.Library.createRoute(tab)) },
                onContinueReadingClick = { navController.navigate(Screen.Bible.route) },
                onBookmarkClick = {
                    bibleViewModel.goToBookmark()
                    navController.navigate(Screen.Bible.route)
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsView(
                bibleViewModel = bibleViewModel,
                authViewModel = authViewModel,
                modifier = screenModifier,
                onSignInClick = { navController.navigate(Screen.Login.route) },
                onLanguageClick = { navController.navigate(Screen.LanguagePicker.route) },
                onLicensesClick = { navController.navigate(Screen.Licenses.route) }
            )
        }

        composable(Screen.LanguagePicker.route) {
            LanguagePickerView(modifier = screenModifier)
        }

        composable(Screen.Licenses.route) {
            LicensesView(modifier = screenModifier)
        }

        composable(
            Screen.Library.route,
            arguments = listOf(navArgument("tab") { type = NavType.StringType })
        ) { backStackEntry ->
            LibraryView(
                bibleViewModel = bibleViewModel,
                modifier = screenModifier,
                initialTab = backStackEntry.arguments?.getString("tab"),
                onOpenDetail = { type, localId ->
                    navController.navigate(Screen.LibraryItemDetail.createRoute(type, localId))
                }
            )
        }

        composable(
            Screen.LibraryItemDetail.route,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
                navArgument("localId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val type = backStackEntry.arguments?.getString("type") ?: Screen.LibraryItemDetail.TYPE_HIGHLIGHT
            val localId = backStackEntry.arguments?.getLong("localId") ?: -1L
            LibraryItemDetailView(
                bibleViewModel = bibleViewModel,
                type = type,
                localId = localId,
                modifier = screenModifier,
                onOpenInBible = {
                    if (type == Screen.LibraryItemDetail.TYPE_HIGHLIGHT) {
                        bibleViewModel.libraryHighlights.value.firstOrNull { it.localId == localId }
                            ?.let { bibleViewModel.openHighlightFromLibrary(it) }
                    } else {
                        bibleViewModel.libraryNotes.value.firstOrNull { it.localId == localId }
                            ?.let { bibleViewModel.openNoteFromLibrary(it) }
                    }
                    navController.navigate(Screen.Bible.route)
                }
            )
        }

        composable(Screen.Login.route) {
            LoginView(
                authViewModel = authViewModel,
                modifier = screenModifier,
                onLoginSuccess = {
                    bibleViewModel.syncReadingProgressFromServer()
                    bibleViewModel.dismissSignInNudge()
                    SyncScheduler.triggerImmediateSync(context)
                    navController.popBackStack()
                },
                onNavigateToRegister = { navController.navigate(Screen.Register.route) }
            )
        }

        composable(Screen.Register.route) {
            RegisterView(
                authViewModel = authViewModel,
                modifier = screenModifier,
                onRegisterSuccess = {
                    bibleViewModel.syncReadingProgressFromServer()
                    bibleViewModel.dismissSignInNudge()
                    SyncScheduler.triggerImmediateSync(context)
                    // Back past Login too, to wherever sign-in started (You tab, a settings page,
                    // the reader's nudge) rather than a fixed screen that may not be on the stack.
                    navController.popBackStack(Screen.Login.route, inclusive = true)
                },
                onNavigateToLogin = { navController.popBackStack() }
            )
        }

        composable(Screen.BookPicker.route) {
            BookPickerView(
                bibleViewModel,
                modifier = screenModifier,
                onBackClick = {
                    navController.popBackStack()
                },
                onChapterClick = { bookId, chapterNumber ->
                    navController.navigate(Screen.VersePicker.createRoute(bookId, chapterNumber))
                }
            )
        }

        composable(
            Screen.VersePicker.route,
            arguments = listOf(
                navArgument("bookId") { type = NavType.IntType },
                navArgument("chapter") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getInt("bookId") ?: 1
            val chapter = backStackEntry.arguments?.getInt("chapter") ?: 1
            VersePickerView(
                bibleViewModel,
                bookId = bookId,
                chapter = chapter,
                modifier = screenModifier,
                onBackClick = {
                    navController.popBackStack()
                } ,
                onVerseClicked = { verse ->
                    // Only now — with book, chapter, AND verse all known together — does the
                    // chapter actually load. Loading eagerly when the chapter was first picked
                    // (defaulting to verse 1) let that load's completion race this later verse
                    // pick and land after it, silently resetting the scroll target back to 1.
                    bibleViewModel.setBook(bookId, chapter, verse)
                    navController.navigate(Screen.Bible.route)
                }
            )

        }

        composable(Screen.VersionPicker.route) {
            VersionPickerView(
                bibleViewModel,
                modifier = screenModifier,
                onVersionClicked = {
                    navController.navigate(Screen.Bible.route)
                }
            )
        }
    }
}

