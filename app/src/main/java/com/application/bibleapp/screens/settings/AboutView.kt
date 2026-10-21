package com.application.bibleapp.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.application.bibleapp.components.GroupedDivider
import com.application.bibleapp.components.GroupedRow
import com.application.bibleapp.components.GroupedSection
import com.application.bibleapp.ui.theme.Spacing

@Composable
fun AboutView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl)
    ) {
        GroupedSection(title = null) {
            GroupedRow(
                title = "TreasuredWord",
                subtitle = "Read, highlight, and keep notes on the Bible",
                icon = Icons.Outlined.AutoStories
            )
            GroupedDivider()
            GroupedRow(
                title = "Version",
                icon = Icons.Outlined.Info,
                value = appVersionName(context)
            )
        }
    }
}

/** From the installed package rather than a hardcoded string, so it can't drift from build.gradle. */
internal fun appVersionName(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
