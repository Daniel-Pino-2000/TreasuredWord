package com.application.bibleapp.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Search
import com.application.bibleapp.BuildConfig
import com.application.bibleapp.navigation.BottomNavigationItem

val bottomNavigationItems = listOfNotNull(
    BottomNavigationItem(
        Screen.Home.titleRes,
        Screen.Home.route,
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    BottomNavigationItem(
        Screen.Bible.titleRes,
        Screen.Bible.route,
        selectedIcon = Icons.Filled.Book,
        unselectedIcon = Icons.Outlined.Book,
    ),
    BottomNavigationItem(
        Screen.Search.titleRes,
        Screen.Search.route,
        selectedIcon = Icons.Filled.Search,
        unselectedIcon = Icons.Outlined.Search,
    ),
    // The Bible Helper (#128) stays out of release builds until #131.
    if (BuildConfig.AI_HELPER_ENABLED) {
        BottomNavigationItem(
            Screen.Ask.titleRes,
            Screen.Ask.route,
            selectedIcon = Icons.Filled.QuestionAnswer,
            unselectedIcon = Icons.Outlined.QuestionAnswer,
        )
    } else {
        null
    },
    BottomNavigationItem(
        Screen.You.titleRes,
        Screen.You.route,
        selectedIcon = Icons.Filled.Person,
        unselectedIcon = Icons.Outlined.Person,
    )

)