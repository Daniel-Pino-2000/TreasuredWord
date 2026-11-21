package com.application.bibleapp.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouVersionBiblesTest {

    @Test
    fun `ids are unique and never clash with bible-helloao ids`() {
        val ids = YouVersionBibles.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { YouVersionBibles.isYouVersion(it) })
        assertFalse(YouVersionBibles.isYouVersion("kjv"))
        assertFalse(YouVersionBibles.isYouVersion("spa_r09"))
    }

    @Test
    fun `every version links its publisher over https and is credited`() {
        YouVersionBibles.all.forEach { bible ->
            val license = bible.toTranslation().license!!
            assertTrue(bible.abbreviation, license.url.startsWith("https://"))
            assertTrue(license.attributionRequired)
            assertFalse("licensed text is never public domain", license.publicDomain)
            assertFalse("licenses are non-commercial", license.commercialUseAllowed)
        }
    }

    @Test
    fun `a YouVersion version no longer offered is withdrawn, offered and helloao ones are not`() {
        assertTrue("EasyEnglish was removed (#381)", YouVersionBibles.isWithdrawn("yv-2079"))
        YouVersionBibles.all.forEach { assertFalse(it.abbreviation, YouVersionBibles.isWithdrawn(it.id)) }
        assertFalse(YouVersionBibles.isWithdrawn("kjv"))
        assertFalse(YouVersionBibles.isWithdrawn("spa_r09"))
    }

    @Test
    fun `Lockman versions carry the notice their license prescribes`() {
        YouVersionBibles.all.filter { it.publisher == "The Lockman Foundation" }.forEach { bible ->
            val notice = bible.notice!!
            assertTrue(bible.abbreviation, "The Lockman Foundation" in notice)
            assertTrue(bible.abbreviation, "Used by permission" in notice)
            assertTrue(bible.abbreviation, "Lockman.org" in notice)
        }
    }
}
