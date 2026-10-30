package com.application.bibleapp.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The language the Bible Helper is asked to answer in (#127). */
class AppLanguageTest {

    @Test
    fun `a supported language is sent as the app's tag`() {
        assertEquals("es", AppLanguage.answerLanguageTag(Locale.forLanguageTag("es-MX")))
        assertEquals("pt-BR", AppLanguage.answerLanguageTag(Locale.forLanguageTag("pt-BR")))
        assertEquals("zh-Hans", AppLanguage.answerLanguageTag(Locale.forLanguageTag("zh-CN")))
        assertEquals("fil", AppLanguage.answerLanguageTag(Locale.forLanguageTag("fil-PH")))
    }

    @Test
    fun `a language the app isn't translated into gets English`() {
        assertEquals("en", AppLanguage.answerLanguageTag(Locale.JAPANESE))
        assertEquals("en", AppLanguage.answerLanguageTag(Locale.forLanguageTag("zh-TW")))
    }
}
