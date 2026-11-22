package com.application.bibleapp.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * Appends a version's tag ("NASB 2020"), linked to [publisherUrl] when it's a licensed version
 * (#383): Lockman asks for the tag after its quoted verses, linked to lockman.org
 * (docs/bible-licensing.md). Plain text when [publisherUrl] is null. [onOpenLink] gets the https
 * URL for ExternalLinks.openPage. [linkColor] defaults to `primary`; on a colored container pass its
 * content color, which the theme already checks against it.
 */
fun AnnotatedString.Builder.appendVersionTag(
    tag: String,
    publisherUrl: String?,
    linkColor: Color,
    onOpenLink: (String) -> Unit
) {
    if (publisherUrl == null) {
        append(tag)
        return
    }
    val styles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    withLink(LinkAnnotation.Clickable("publisher", styles) { onOpenLink(publisherUrl) }) { append(tag) }
}

/** [MaterialTheme]'s link color for [appendVersionTag] on a surface. */
val versionTagLinkColor: Color
    @Composable get() = MaterialTheme.colorScheme.primary
