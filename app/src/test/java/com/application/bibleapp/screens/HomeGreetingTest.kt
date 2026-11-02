package com.application.bibleapp.screens

import com.application.bibleapp.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's greeting follows the hour it's recomputed at when the app comes back (#188). */
class HomeGreetingTest {

    @Test
    fun `morning runs from 5 to 11`() {
        assertEquals(R.string.home_greeting_morning, homeGreetingRes(5))
        assertEquals(R.string.home_greeting_morning, homeGreetingRes(11))
    }

    @Test
    fun `afternoon runs from 12 to 17`() {
        assertEquals(R.string.home_greeting_afternoon, homeGreetingRes(12))
        assertEquals(R.string.home_greeting_afternoon, homeGreetingRes(17))
    }

    @Test
    fun `evening covers the rest of the day and the night`() {
        assertEquals(R.string.home_greeting_evening, homeGreetingRes(18))
        assertEquals(R.string.home_greeting_evening, homeGreetingRes(23))
        assertEquals(R.string.home_greeting_evening, homeGreetingRes(0))
        assertEquals(R.string.home_greeting_evening, homeGreetingRes(4))
    }
}
