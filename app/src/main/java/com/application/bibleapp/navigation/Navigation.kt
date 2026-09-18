package com.application.bibleapp.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import com.application.bibleapp.screens.LibraryView
import com.application.bibleapp.screens.LoginView
import com.application.bibleapp.screens.RegisterView
import com.application.bibleapp.screens.SearchView
import com.application.bibleapp.screens.SettingsView
import com.application.bibleapp.screens.VersePickerView
import com.application.bibleapp.screens.VersionPickerView
import com.application.bibleapp.viewmodel.AuthViewModel
import com.application.bibleapp.viewmodel.BibleViewModel
import com.application.bibleapp.worker.SyncScheduler

@Composable
fun Navigation(
    navController: NavHostController,
    padding: PaddingValues,
    bibleViewModel: BibleViewModel,
    authViewModel: AuthViewModel
) {
    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {

        composable(Screen.Home.route) {
            HomeView(
                bibleViewModel = bibleViewModel,
                modifier = Modifier.padding(padding),
                onContinueReadingClick = { navController.navigate(Screen.Bible.route) },
                onSearchClick = { navController.navigate(Screen.Search.route) }
            )
        }

        composable(Screen.Bible.route) {
            BibleView(bibleViewModel, modifier = Modifier.padding(padding))
        }

        composable(Screen.Search.route) {
            SearchView(bibleViewModel, modifier = Modifier.padding(padding)) { bookId, chapter, verse ->
                // Clear search first to avoid rendering issues
                bibleViewModel.clearSearchResults()
                bibleViewModel.setBook(bookId, chapter, verse)

                // Navigate safely after clearing
                navController.navigate(Screen.Bible.route)
            }
        }


        composable(Screen.More.route) {
            SettingsView(
                bibleViewModel = bibleViewModel,
                authViewModel = authViewModel,
                modifier = Modifier.padding(padding),
                onSignInClick = { navController.navigate(Screen.Login.route) },
                onLibraryClick = { navController.navigate(Screen.Library.route) }
            )
        }

        composable(Screen.Library.route) {
            LibraryView(
                bibleViewModel = bibleViewModel,
                modifier = Modifier.padding(padding),
                onVerseClick = { bookId, chapter, verse ->
                    bibleViewModel.setBook(bookId, chapter, verse)
                    navController.navigate(Screen.Bible.route)
                }
            )
        }

        composable(Screen.Login.route) {
            LoginView(
                authViewModel = authViewModel,
                modifier = Modifier.padding(padding),
                onLoginSuccess = {
                    bibleViewModel.syncReadingProgressFromServer()
                    SyncScheduler.triggerImmediateSync(context)
                    navController.popBackStack()
                },
                onNavigateToRegister = { navController.navigate(Screen.Register.route) }
            )
        }

        composable(Screen.Register.route) {
            RegisterView(
                authViewModel = authViewModel,
                modifier = Modifier.padding(padding),
                onRegisterSuccess = {
                    bibleViewModel.syncReadingProgressFromServer()
                    SyncScheduler.triggerImmediateSync(context)
                    navController.popBackStack(Screen.More.route, inclusive = false)
                },
                onNavigateToLogin = { navController.popBackStack() }
            )
        }

        composable(Screen.BookPicker.route) {
            BookPickerView(
                bibleViewModel,
                modifier = Modifier.padding(padding),
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
                modifier = Modifier.padding(padding),
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
                modifier = Modifier.fillMaxSize().padding(padding),
                onVersionClicked = {
                    navController.navigate(Screen.Bible.route)
                }
            )
        }
    }
}

