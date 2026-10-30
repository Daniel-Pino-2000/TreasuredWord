package com.application.bibleapp.components

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.application.bibleapp.R
import com.application.bibleapp.data.model.AiVersePassage
import com.application.bibleapp.data.model.PassageVerse
import com.application.bibleapp.ui.theme.BibleAppTheme
import com.application.bibleapp.utils.VerseReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Bible Helper's verse cards (#129) on a device: what they show and where they open. */
@RunWith(AndroidJUnit4::class)
class AiVerseCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val readChapter = resources.getString(R.string.ai_verse_read_chapter)

    @Test
    fun aCardShowsTheReferenceTheVerseAndTheTranslation() {
        var opened = 0
        compose.setContent {
            BibleAppTheme {
                AiVerseCardContent(
                    reference = "Juan 3:16",
                    text = AiVerseCardText.Loaded(
                        AiVersePassage("spa", listOf(PassageVerse(16, "Porque de tal manera amó Dios al mundo"))),
                        versionLabel = "RV1909"
                    ),
                    onReadChapter = { opened++ }
                )
            }
        }

        compose.onNodeWithText("Juan 3:16").assertExists()
        compose.onNodeWithText("Porque de tal manera amó Dios al mundo").assertExists()
        compose.onNodeWithText("RV1909").assertExists()
        compose.onNodeWithText(readChapter).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun aPassageThatCouldNotLoadSaysSo() {
        compose.setContent {
            BibleAppTheme { AiVerseCardContent("Salmos 23", AiVerseCardText.Missing, onReadChapter = {}) }
        }

        compose.onNodeWithText(resources.getString(R.string.ai_verse_not_found)).assertExists()
    }

    @Test
    fun readTheWholeChapterOpensAtThePassagesFirstVerse() {
        val opened = mutableListOf<Triple<Int, Int, Int>>()
        compose.setContent {
            BibleAppTheme {
                AiVerseCardList(listOf(VerseReference(43, 3, 16, 18), VerseReference(19, 23))) { reference ->
                    AiVerseCardContent(
                        reference = "${reference.bookId} ${reference.chapter}",
                        text = AiVerseCardText.Missing,
                        onReadChapter = { opened += Triple(reference.bookId, reference.chapter, reference.startVerse ?: 1) }
                    )
                }
            }
        }

        compose.onAllNodesWithText(readChapter)[0].performClick()
        compose.onAllNodesWithText(readChapter)[1].performClick()
        assertEquals(listOf(Triple(43, 3, 16), Triple(19, 23, 1)), opened)
    }

    @Test
    fun moreThanFiveCardsWaitBehindShowMore() {
        val references = (1..7).map { VerseReference(19, it) }
        compose.setContent {
            BibleAppTheme { AiVerseCardList(references) { Text("Psalm ${it.chapter}") } }
        }

        compose.onAllNodesWithText("Psalm", substring = true).assertCountEquals(5)
        compose.onNodeWithText(resources.getQuantityString(R.plurals.ai_verse_cards_show_more, 2, 2)).performClick()
        compose.onAllNodesWithText("Psalm", substring = true).assertCountEquals(7)
    }
}
