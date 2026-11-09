package com.application.bibleapp.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.application.bibleapp.R
import com.application.bibleapp.ui.theme.BibleAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Library's swipe-to-delete (#243): only a real swipe deletes, and TalkBack can delete too. */
@RunWith(AndroidJUnit4::class)
class SwipeToDeleteRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val deleteLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.action_delete)
    private var deleted = 0

    private fun showCard() {
        compose.setContent {
            BibleAppTheme {
                SwipeToDeleteRow(onDelete = { deleted++ }) {
                    Surface(Modifier.fillMaxWidth().height(96.dp)) { Text("Card") }
                }
            }
        }
    }

    @Test
    fun aShortDragSpringsBack() {
        showCard()
        compose.onNodeWithText("Card").performTouchInput {
            swipeLeft(startX = right - 10f, endX = right - width * 0.3f, durationMillis = 400)
        }
        compose.waitForIdle()
        assertEquals(0, deleted)
    }

    @Test
    fun aShortQuickFlickSpringsBack() {
        showCard()
        compose.onNodeWithText("Card").performTouchInput {
            swipeLeft(startX = right - 10f, endX = right - width * 0.3f, durationMillis = 40)
        }
        compose.waitForIdle()
        assertEquals(0, deleted)
    }

    @Test
    fun swipingPastHalfDeletesOnce() {
        showCard()
        compose.onNodeWithText("Card").performTouchInput {
            swipeLeft(startX = right - 10f, endX = right - width * 0.75f, durationMillis = 400)
        }
        compose.waitForIdle()
        assertEquals(1, deleted)
    }

    @Test
    fun talkBackCanDeleteWithTheCustomAction() {
        showCard()
        val actions = compose
            .onNode(hasAnyDescendant(hasText("Card")) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf(deleteLabel), actions.map { it.label })
        actions.single().action()
        assertEquals(1, deleted)
    }
}
