package com.application.bibleapp.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * A bottom sheet with a text field in it (#240). Back closes the keyboard first, as it does
 * everywhere else, and only the next Back closes the sheet.
 *
 * Material3 1.3's [ModalBottomSheet] takes Back ahead of the keyboard (its callback is registered
 * at overlay priority), so one Back while typing closed the keyboard *and* the sheet, losing what
 * was typed. It also slides the sheet away before calling onDismissRequest, so the note editor's
 * "Discard note?" appeared with the note already gone. The sheet's own Back handling is turned off
 * here, and a [BackHandler] inside the sheet does the same job at normal priority, behind the
 * keyboard. Swiping down and tapping outside are unchanged.
 *
 * Laid out like the app's other sheets: full height straight away and clear of the status bar.
 *
 * @param onBack what Back does once the keyboard is closed; by default the sheet slides away and
 *   then [onDismissRequest] runs, as Material's own Back does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEntrySheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.large,
        // Only matters once the sheet is as tall as the window (landscape): stop below the
        // status bar instead of running the drag handle up under it.
        modifier = modifier.statusBarsPadding(),
        dragHandle = dragHandle,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false)
    ) {
        // Registered in the sheet's own window, after the sheet's always-on callback, so it
        // runs first; the keyboard's callback, added when it opens, runs before both.
        BackHandler {
            if (onBack != null) {
                onBack()
            } else {
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    if (!sheetState.isVisible) onDismissRequest()
                }
            }
        }
        content()
    }
}
