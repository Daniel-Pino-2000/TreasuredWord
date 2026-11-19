package com.application.bibleapp.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * One line of [text], shrunk step by step until it fits its width, down to [minFontSize]. For
 * short labels in tight, equal-width slots: with Memorize (#362) the selection toolbar has five
 * actions and Library three tabs, and a long word ("Memorize", "Markierungen") broke in the
 * middle of itself onto a second line. Drawn only once it fits, so it never flashes at the larger
 * size first.
 */
@Composable
fun FitOnOneLineText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
    minFontSize: TextUnit = 9.sp
) {
    var fitted by remember(text, style) { mutableStateOf(style) }
    var fits by remember(text, style) { mutableStateOf(false) }
    Text(
        text = text,
        style = fitted,
        maxLines = 1,
        softWrap = false,
        textAlign = textAlign,
        modifier = modifier.drawWithContent { if (fits) drawContent() },
        onTextLayout = { result ->
            if (result.didOverflowWidth && fitted.fontSize > minFontSize) {
                fitted = fitted.copy(fontSize = fitted.fontSize * 0.92f, letterSpacing = 0.sp)
            } else {
                fits = true
            }
        }
    )
}
