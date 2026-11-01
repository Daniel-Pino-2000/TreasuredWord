package com.application.bibleapp.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** The code field keeps only the digits of what's typed or pasted (#159). */
class EmailCodeInputTest {

    @Test
    fun `a pasted code with spaces or words around it keeps its six digits`() {
        assertEquals("123456", emailCodeInput("123 456"))
        assertEquals("042917", emailCodeInput(" Code: 042917 "))
    }

    @Test
    fun `no more than six digits are kept`() {
        assertEquals("123456", emailCodeInput("12345678"))
    }

    @Test
    fun `letters and other digit systems are dropped`() {
        assertEquals("12", emailCodeInput("1a2b"))
        assertEquals("", emailCodeInput("١٢٣"))
    }
}
