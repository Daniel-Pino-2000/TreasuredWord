package com.application.bibleapp.server.ai

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The instructions the model gets (#124): the real resource files, filled in. */
class SystemPromptTest {

    @Test
    fun everyLanguageGetsCompleteInstructions() {
        for ((tag, name) in SystemPrompt.languageNames) {
            val prompt = SystemPrompt.forLanguage(tag)
            assertTrue("Always answer in $name" in prompt, tag)
            // The doctrine summary is inside, under "What you believe".
            assertTrue("Baptist Faith and Message 2000" in prompt, tag)
            assertTrue("GEN, EXO, LEV" in prompt && "3JN, JUD, REV" in prompt, tag)
            // No placeholder left unfilled, and no note meant for developers.
            assertFalse(Regex("\\{[a-zA-Z]+\\}").containsMatchIn(prompt), tag)
            assertFalse("<!--" in prompt || "-->" in prompt, tag)
        }
    }

    @Test
    fun anUnknownLanguageIsRefused() {
        assertFailsWith<IllegalArgumentException> { SystemPrompt.forLanguage("xx") }
    }

    @Test
    fun theAppsLanguagesAndBookCodesAreAllHere() {
        // The app's AppLanguage.supportedTags and data/model/UsfmBookCodes.kt (#129).
        assertTrue(SystemPrompt.languageNames.keys == setOf("de", "en", "es", "fil", "fr", "id", "sw", "pt-BR", "ko", "zh-Hans"))
        assertTrue(UsfmBookCodes.codes.size == 66 && UsfmBookCodes.codes.toSet().size == 66)
    }
}
