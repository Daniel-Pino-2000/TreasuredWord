package com.application.bibleapp.screens.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.application.bibleapp.R
import com.application.bibleapp.ui.theme.Spacing
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.util.withJson

/**
 * Settings → Open-source licenses (#99): every library in the build with its license text, which
 * the Apache License asks us to ship with the app. The list is generated at build time by the
 * AboutLibraries plugin from the release dependencies, plus app/aboutlibraries/libraries/ for what
 * Gradle can't see (OpenCC's character table). Tapping a library shows its full license.
 */
@Composable
fun LicensesView(modifier: Modifier = Modifier) {
    LibrariesContainer(
        modifier = modifier.fillMaxSize(),
        // By resource id, not looked up by name: with shrinkResources on, R8 could otherwise drop
        // the generated JSON as unused and the release build would show an empty list.
        librariesBlock = { context -> Libs.Builder().withJson(context, R.raw.aboutlibraries).build() },
        contentPadding = PaddingValues(vertical = Spacing.sm)
    )
}
