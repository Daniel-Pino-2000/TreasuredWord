package com.application.bibleapp.ui.theme

import androidx.compose.ui.graphics.Color

// Warm, paper-like palette — reading comfort over tech-dashboard contrast.
// Every Material3 color role used by the app is defined explicitly for both
// modes (see Theme.kt) so nothing falls back to the M3 default purple/grey.

// Light
val LightBackground = Color(0xFFFAF6EF)
val LightSurface = Color(0xFFF1EAD9)
val LightSurfaceVariant = Color(0xFFE8DFC9)
val LightOnBackground = Color(0xFF2B2620)
val LightOnSurface = Color(0xFF2B2620)
val LightOnSurfaceVariant = Color(0xFF7A6F5D)
val LightOutline = Color(0xFFD9CFBB)
val LightOutlineVariant = Color(0xFFE3DAC4)
val LightPrimary = Color(0xFFA6743A)
val LightOnPrimary = Color(0xFFFFFBF2)
val LightPrimaryContainer = Color(0xFFEDDFC4)
val LightOnPrimaryContainer = Color(0xFF3D2D12)
// Secondary mirrors primary's warm gold family — several M3 components (nav
// selection indicator, filter chips) default to secondaryContainer for their
// "selected" state, and leaving it undefined falls back to the M3 template purple.
val LightSecondary = LightPrimary
val LightOnSecondary = LightOnPrimary
val LightSecondaryContainer = LightPrimaryContainer
val LightOnSecondaryContainer = LightOnPrimaryContainer
// Surface-container ladder (lowest → highest) — ModalBottomSheet, menus, dialogs, search bars
// and others default to these roles, not `surface`, so leaving them undefined painted every
// bottom sheet in M3's template lavender.
val LightSurfaceContainerLowest = Color(0xFFFFFDF8)
val LightSurfaceContainerLow = Color(0xFFFBF6EC)
val LightSurfaceContainer = Color(0xFFF5EFE2)
val LightSurfaceContainerHigh = Color(0xFFF1EAD9)
val LightSurfaceContainerHighest = Color(0xFFE8DFC9)
val LightSurfaceBright = Color(0xFFFFFBF4)
val LightSurfaceDim = Color(0xFFE3DAC4)
val LightError = Color(0xFFB3423B)
val LightOnError = Color(0xFFFFFBF2)

// Dark — soft dark greys, not pure black, to reduce halation/eye strain.
val DarkBackground = Color(0xFF1E1B16)
val DarkSurface = Color(0xFF26221C)
val DarkSurfaceVariant = Color(0xFF332D24)
val DarkOnBackground = Color(0xFFEDE6D6)
val DarkOnSurface = Color(0xFFEDE6D6)
val DarkOnSurfaceVariant = Color(0xFFA99C86)
val DarkOutline = Color(0xFF453D30)
val DarkOutlineVariant = Color(0xFF3A332A)
val DarkPrimary = Color(0xFFD9A85C)
val DarkOnPrimary = Color(0xFF2B2216)
val DarkPrimaryContainer = Color(0xFF3D3220)
val DarkOnPrimaryContainer = Color(0xFFF0DDB8)
val DarkSecondary = DarkPrimary
val DarkOnSecondary = DarkOnPrimary
val DarkSecondaryContainer = DarkPrimaryContainer
val DarkOnSecondaryContainer = DarkOnPrimaryContainer
val DarkSurfaceContainerLowest = Color(0xFF1A1712)
val DarkSurfaceContainerLow = Color(0xFF24201A)
val DarkSurfaceContainer = Color(0xFF29241E)
val DarkSurfaceContainerHigh = Color(0xFF2F2A22)
val DarkSurfaceContainerHighest = Color(0xFF383127)
val DarkSurfaceBright = Color(0xFF3A342A)
val DarkSurfaceDim = Color(0xFF1A1712)
val DarkError = Color(0xFFE08585)
val DarkOnError = Color(0xFF2B1212)
