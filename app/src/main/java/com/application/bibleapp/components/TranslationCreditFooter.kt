package com.application.bibleapp.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.application.bibleapp.R
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.viewmodel.TranslationCredit

/** Where every downloadable Bible's text comes from; the credit names it next to its link. */
private const val BIBLE_TEXT_SOURCE_NAME = "bible.helloao.org"
private const val BIBLE_TEXT_SOURCE_URL = "https://bible.helloao.org"

/**
 * The credit under the last verse of every chapter (#98): the translation's title, its license
 * (most downloadable Bibles require the credit, and some a notice), and where the text came from.
 * At the end of the chapter rather than only in the version picker, so it's always with the text
 * it credits, on- or offline. [onOpenLink] gets https URLs for ExternalLinks.openPage.
 */
@Composable
fun TranslationCreditFooter(
    credit: TranslationCredit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
    )
    val publicDomain = stringResource(R.string.translation_credit_public_domain)
    val licenseLabel = stringResource(R.string.translation_credit_license)
    val source = stringResource(R.string.translation_credit_source, BIBLE_TEXT_SOURCE_NAME)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 0.5.dp,
            modifier = Modifier.padding(bottom = Spacing.sm)
        )
        Text(text = credit.title, style = MaterialTheme.typography.labelLarge, color = textColor)

        val license = credit.license
        when {
            credit.isBundled -> Text(text = publicDomain, style = MaterialTheme.typography.bodySmall, color = textColor)
            license != null -> Text(
                text = buildAnnotatedString {
                    if (license.publicDomain) append("$publicDomain · ")
                    withLink(LinkAnnotation.Clickable("license", linkStyles) { onOpenLink(license.url) }) {
                        append(licenseLabel)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = textColor
            )
        }
        license?.notice?.let { notice ->
            Text(text = notice, style = MaterialTheme.typography.bodySmall, color = textColor)
        }
        if (!credit.isBundled) {
            Text(
                text = linkedSourceName(source, linkStyles) { onOpenLink(BIBLE_TEXT_SOURCE_URL) },
                style = MaterialTheme.typography.bodySmall,
                color = textColor
            )
        }
    }
}

/** [text] with the source's name in it made a link; plain text if a translation dropped the name. */
private fun linkedSourceName(text: String, styles: TextLinkStyles, onClick: () -> Unit): AnnotatedString {
    val start = text.indexOf(BIBLE_TEXT_SOURCE_NAME)
    if (start < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, start))
        withLink(LinkAnnotation.Clickable("source", styles) { onClick() }) { append(BIBLE_TEXT_SOURCE_NAME) }
        append(text.substring(start + BIBLE_TEXT_SOURCE_NAME.length))
    }
}
