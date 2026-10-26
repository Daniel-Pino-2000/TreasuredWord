package com.application.bibleapp.viewmodel

import com.application.bibleapp.data.model.BibleTranslation
import com.application.bibleapp.data.model.DownloadedVersionInfo
import com.application.bibleapp.data.model.TextDirection
import com.application.bibleapp.data.model.TranslationLicense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationCreditTest {

    private val catalogLicense = TranslationLicense(url = "https://ebible.org/Scriptures/details.php?id=porbr2018")
    private val savedLicense = TranslationLicense(url = "https://ebible.org/saved")

    private val catalog = listOf(
        BibleTranslation(
            id = "por_blj",
            displayName = "BLJ",
            nativeName = "Bíblia Livre",
            languageName = "Portuguese",
            languageCode = "por",
            textDirection = TextDirection.LTR,
            license = catalogLicense
        )
    )

    @Test
    fun `the bundled KJV is credited as public domain`() {
        val credit = translationCreditFor("kjv", catalog, emptyMap())

        assertEquals("King James Version (KJV)", credit.title)
        assertTrue(credit.isBundled)
        assertNull(credit.license)
    }

    @Test
    fun `the catalog gives the full title and license`() {
        val credit = translationCreditFor("por_blj", catalog, emptyMap())

        assertEquals("Bíblia Livre (BLJ)", credit.title)
        assertEquals(catalogLicense, credit.license)
    }

    @Test
    fun `offline, the title and license saved with the download are used`() {
        val downloaded = mapOf(
            "por_blj" to DownloadedVersionInfo("por_blj", schemaVersion = 5, name = "BLJ", fullName = "Bíblia Livre", license = savedLicense)
        )

        val credit = translationCreditFor("por_blj", emptyList(), downloaded)

        assertEquals("Bíblia Livre (BLJ)", credit.title)
        assertEquals(savedLicense, credit.license)
    }

    @Test
    fun `the license saved with the download wins over the catalog's`() {
        val downloaded = mapOf("por_blj" to DownloadedVersionInfo("por_blj", schemaVersion = 5, license = savedLicense))

        assertEquals(savedLicense, translationCreditFor("por_blj", catalog, downloaded).license)
    }

    @Test
    fun `an older download without a saved license falls back to the catalog`() {
        val downloaded = mapOf("por_blj" to DownloadedVersionInfo("por_blj", schemaVersion = 5, name = "BLJ"))

        assertEquals(catalogLicense, translationCreditFor("por_blj", catalog, downloaded).license)
    }

    @Test
    fun `a title that is just the abbreviation isn't repeated`() {
        val downloaded = mapOf("twi_asa" to DownloadedVersionInfo("twi_asa", schemaVersion = 5, name = "ASA", fullName = "ASA"))

        assertEquals("ASA", translationCreditFor("twi_asa", emptyList(), downloaded).title)
    }
}
