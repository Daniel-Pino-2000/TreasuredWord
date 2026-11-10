package com.application.bibleapp.components

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.application.bibleapp.R
import com.application.bibleapp.ui.theme.BibleAppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** The verse selection toolbar's actions: icon and label centered on their buttons (#269). */
@RunWith(AndroidJUnit4::class)
class VerseSelectionToolbarTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun showToolbar() {
        compose.setContent {
            BibleAppTheme {
                VerseSelectionToolbar(
                    selectionCount = 1,
                    onColorSelected = {},
                    onCopy = {},
                    onShare = {},
                    onShareImage = {},
                    onAddNote = {},
                    onCancel = {},
                    onAsk = {}
                )
            }
        }
    }

    @Test
    fun everyActionIsCenteredOnItsButton() {
        showToolbar()
        val contents = compose.onAllNodes(hasTestTag(SELECTION_ACTION_CONTENT_TAG), useUnmergedTree = true)
            .fetchSemanticsNodes()
        // Copy, Share, Ask and Note.
        assertEquals(4, contents.size)
        val tolerance = with(compose.density) { 2.dp.toPx() }
        contents.forEach { content ->
            // The Box between them has no semantics, so the parent is the button (its Surface).
            val button = content.parent ?: error("action content has no button")
            val offset = content.boundsInRoot.center.x - button.boundsInRoot.center.x
            assertTrue(
                "action off-center by ${offset}px in a ${button.boundsInRoot.width}px button",
                abs(offset) <= tolerance
            )
        }
    }

    @Test
    fun shareStillOpensItsTextOrImageMenu() {
        showToolbar()
        compose.onNodeWithText(context.getString(R.string.action_share)).performClick()
        compose.onNodeWithText(context.getString(R.string.share_as_image)).assertExists()
    }
}
