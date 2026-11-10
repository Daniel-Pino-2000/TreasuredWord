package com.application.bibleapp.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.application.bibleapp.R
import com.application.bibleapp.ui.theme.Spacing
import com.application.bibleapp.ui.theme.VerseImageStyle
import com.application.bibleapp.ui.theme.isCompactHeight
import com.application.bibleapp.verseimage.VerseImageFormat
import com.application.bibleapp.verseimage.VerseImageOptions
import com.application.bibleapp.verseimage.VerseImageRenderer
import com.application.bibleapp.verseimage.VerseImageRequest
import com.application.bibleapp.verseimage.VerseImageSharing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SWATCH_SIZE = 40.dp
private val SWATCH_TOUCH = 48.dp
private val PREVIEW_MAX_HEIGHT = 340.dp

/**
 * The verse image editor (#254): a live preview and the choices that change it, then Share. The
 * preview is the very bitmap that gets shared, drawn by [VerseImageRenderer] off the main thread
 * each time a choice changes. A passage too long to read as an image offers text instead.
 *
 * Its own [SnackbarHost], since the sheet is a separate window drawn over the screen's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerseImageSheet(
    request: VerseImageRequest,
    options: VerseImageOptions,
    onOptionsChange: (VerseImageOptions) -> Unit,
    onShareText: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.large,
        // Only matters once the sheet is as tall as the window (landscape): stop below the status bar.
        modifier = Modifier.statusBarsPadding()
    ) {
        Box {
            if (request.content.isTooLongForImage) {
                TooLongContent(onShareText = {
                    onShareText(request.shareText)
                    onDismiss()
                })
            } else {
                EditorContent(request, options, onOptionsChange, snackbarHostState)
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            )
        }
    }
}

@Composable
private fun EditorContent(
    request: VerseImageRequest,
    options: VerseImageOptions,
    onOptionsChange: (VerseImageOptions) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Keeps showing the last image while the next one is drawn, so a change never flashes empty.
    val bitmap by produceState<Bitmap?>(null, request.content, options) {
        value = withContext(Dispatchers.Default) { VerseImageRenderer.render(request.content, options) }
    }
    val failedMessage = stringResource(R.string.verse_image_failed)
    val share: () -> Unit = {
        bitmap?.let { image ->
            scope.launch {
                try {
                    val uri = VerseImageSharing.writeToCache(context, image)
                    context.startActivity(VerseImageSharing.shareIntent(uri))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A full disk, or no app able to receive an image.
                    snackbarHostState.showSnackbar(failedMessage)
                }
            }
        }
    }

    val preview: @Composable (Modifier) -> Unit = { modifier ->
        VerseImagePreview(bitmap, options.format, request.content.creditLine, modifier)
    }
    val controls: @Composable () -> Unit = {
        VerseImageControls(request, options, onOptionsChange, onShare = share, shareEnabled = bitmap != null)
    }

    if (isCompactHeight()) {
        // Landscape: the preview beside the controls, which scroll on their own.
        Row(
            modifier = Modifier
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.sm)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xl)
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                preview(Modifier.heightIn(max = 260.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                SheetTitle()
                controls()
            }
        }
    } else {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            SheetTitle()
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                preview(Modifier.heightIn(max = PREVIEW_MAX_HEIGHT))
            }
            controls()
        }
    }
}

@Composable
private fun SheetTitle() {
    Text(
        text = stringResource(R.string.share_as_image),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun VerseImagePreview(bitmap: Bitmap?, format: VerseImageFormat, creditLine: String, modifier: Modifier) {
    // A thin line around it, since some styles are nearly the sheet's own color.
    val frame = Modifier
        .aspectRatio(format.width.toFloat() / format.height, matchHeightConstraintsFirst = true)
        .clip(MaterialTheme.shapes.medium)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
    if (bitmap == null || bitmap.width.toFloat() / bitmap.height != format.width.toFloat() / format.height) {
        // First draw, or a new size still being drawn: a placeholder of the new shape.
        Box(modifier = modifier.then(frame).background(MaterialTheme.colorScheme.surfaceVariant))
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.verse_image_preview_description, creditLine),
            contentScale = ContentScale.Fit,
            modifier = modifier.then(frame)
        )
    }
}

@Composable
private fun VerseImageControls(
    request: VerseImageRequest,
    options: VerseImageOptions,
    onOptionsChange: (VerseImageOptions) -> Unit,
    onShare: () -> Unit,
    shareEnabled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionLabel(stringResource(R.string.verse_image_style))
        StyleSwatches(selected = options.style, onSelect = { onOptionsChange(options.copy(style = it)) })

        SectionLabel(stringResource(R.string.verse_image_size))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            VerseImageFormat.entries.forEachIndexed { index, format ->
                SegmentedButton(
                    selected = options.format == format,
                    onClick = { onOptionsChange(options.copy(format = format)) },
                    shape = SegmentedButtonDefaults.itemShape(index, VerseImageFormat.entries.size)
                ) {
                    Text(stringResource(format.labelRes), maxLines = 1)
                }
            }
        }

        if (request.content.hasSeveralVerses) {
            SwitchRow(stringResource(R.string.verse_image_verse_numbers), options.showVerseNumbers) {
                onOptionsChange(options.copy(showVerseNumbers = it))
            }
        }
        SwitchRow(stringResource(R.string.verse_image_centered), options.centered) {
            onOptionsChange(options.copy(centered = it))
        }
        SwitchRow(stringResource(R.string.verse_image_show_mark), options.showMark) {
            onOptionsChange(options.copy(showMark = it))
        }

        Button(
            onClick = onShare,
            enabled = shareEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.sm)
        ) {
            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text(stringResource(R.string.action_share), modifier = Modifier.padding(start = Spacing.sm))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xs)
    )
}

/** One circle per style, painted with its background and its text color's "Aa". */
@Composable
private fun StyleSwatches(selected: VerseImageStyle, onSelect: (VerseImageStyle) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        VerseImageStyle.entries.forEach { style ->
            val name = stringResource(style.labelRes)
            val isSelected = style == selected
            Box(
                modifier = Modifier
                    // 48dp each, so all six fit across a phone in portrait (#254).
                    .size(SWATCH_TOUCH)
                    .selectable(
                        selected = isSelected,
                        interactionSource = null,
                        indication = ripple(bounded = false, radius = SWATCH_TOUCH / 2),
                        role = Role.RadioButton,
                        onClick = { onSelect(style) }
                    )
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center
            ) {
                val ring = if (isSelected) {
                    Modifier
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .padding(Spacing.xs)
                } else {
                    Modifier.padding(Spacing.xs)
                }
                Box(modifier = ring) {
                    val fill = if (style.hasGradient) {
                        Brush.verticalGradient(listOf(Color(style.background), Color(style.backgroundEnd)))
                    } else {
                        SolidColor(Color(style.background))
                    }
                    Box(
                        modifier = Modifier
                            .size(SWATCH_SIZE - Spacing.xs)
                            .clip(CircleShape)
                            .background(fill)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        // A sample of the style's text color; the swatch's name is what TalkBack reads.
                        Text(
                            text = "Aa",
                            color = Color(style.text),
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Serif),
                            modifier = Modifier.clearAndSetSemantics {}
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SWATCH_TOUCH)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun TooLongContent(onShareText: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = Spacing.xl)
            .padding(bottom = Spacing.xl)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        SheetTitle()
        Text(
            text = stringResource(R.string.verse_image_too_long),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onShareText, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Outlined.Notes, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text(stringResource(R.string.share_as_text), modifier = Modifier.padding(start = Spacing.sm))
        }
    }
}
