package com.application.bibleapp.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.application.bibleapp.R
import com.application.bibleapp.ui.theme.Spacing

/*
 * The logos of the sites the app links to (#405), shown next to their links so it's clear whose
 * content it is. Both are bundled, never downloaded, and never recolored or redrawn.
 *
 * - BibleProject: from the "Download Logo Files" ZIP on bibleproject.com/brand (checked
 *   2026-11-25). Their rules: the full logo on light backgrounds, the white version on dark
 *   ones, unaltered, with space around it. Their legal team replied (2026-11-25) that this use
 *   looks in line with their Terms of Use. The Spanish site goes by "Proyecto Biblia", whose
 *   logo isn't in that ZIP, so Spanish keeps the name in text instead.
 * - GotQuestions.org: the horizontal full-color image from gotquestions.org/promote.html
 *   (checked 2026-11-25), offered to anyone promoting the site. There's no version for dark
 *   backgrounds, so there it sits on a small white tile, as third-party logos usually do.
 *
 * Neither implies a partnership: never word anything near them as "official" or "partner".
 */

/** BibleProject's logo, in the version their brand page gives for a [background] this light. */
@Composable
fun BibleProjectLogo(background: Color, height: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(
            if (background.isLight()) R.drawable.bibleproject_logo else R.drawable.bibleproject_logo_white
        ),
        contentDescription = stringResource(R.string.source_bibleproject),
        modifier = modifier.height(height)
    )
}

/** GotQuestions.org's full-color logo, on a white tile when [background] is dark. */
@Composable
fun GotQuestionsLogo(background: Color, height: Dp, modifier: Modifier = Modifier) {
    val logo = @Composable {
        Image(
            painter = painterResource(R.drawable.gotquestions_logo),
            contentDescription = stringResource(R.string.source_gotquestions),
            modifier = Modifier.height(height)
        )
    }
    if (background.isLight()) {
        Box(modifier = modifier) { logo() }
    } else {
        Box(
            modifier = modifier
                // Third-party branding: their logo needs a white background, whatever the theme.
                .background(Color.White, MaterialTheme.shapes.small)
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
        ) { logo() }
    }
}

private fun Color.isLight() = luminance() > 0.5f
