package com.application.bibleapp.data.remote

import com.application.bibleapp.data.model.TextDirection
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelloAoDtosTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun catalogEntry(id: String, licenseFields: String) = json.decodeFromString(
        HelloAoTranslationsResponseDto.serializer(),
        """
        {"translations": [{
          "id": "$id", "name": "Bíblia Livre", "englishName": "Portuguese Bíblia Livre", "shortName": "BLJ",
          "language": "por", "textDirection": "ltr", "numberOfBooks": 66,
          "sha256": "ignored", "availableFormats": ["json"]$licenseFields
        }]}
        """.trimIndent()
    ).translations.single()

    @Test
    fun `prefers shortName as the display name when present`() {
        val dto = HelloAoTranslationDto(
            id = "BSB", name = "Berean Standard Bible", englishName = "Berean Standard Bible",
            shortName = "BSB", language = "eng", languageEnglishName = "English"
        )

        val translation = dto.toBibleTranslation()

        assertEquals("BSB", translation.displayName)
        assertEquals("Berean Standard Bible", translation.nativeName)
        assertEquals("English", translation.languageName)
        assertEquals(TextDirection.LTR, translation.textDirection)
    }

    @Test
    fun `falls back to englishName when shortName is missing`() {
        val dto = HelloAoTranslationDto(
            id = "GHT", name = "Garth's Hyper-literal Translation", englishName = "Garth's Hyper-literal Translation",
            shortName = null, language = "eng"
        )

        assertEquals("Garth's Hyper-literal Translation", dto.toBibleTranslation().displayName)
    }

    @Test
    fun `rtl text direction is preserved for right-to-left languages`() {
        val dto = HelloAoTranslationDto(
            id = "ARBNAV", name = "كتاب الحياة", englishName = "New Arabic Version (Book of Life)",
            shortName = "NAV", language = "arb", textDirection = "rtl",
            languageName = "العربية", languageEnglishName = "Arabic"
        )

        val translation = dto.toBibleTranslation()

        assertEquals(TextDirection.RTL, translation.textDirection)
        assertEquals("Arabic", translation.languageName)
        assertEquals("كتاب الحياة", translation.nativeName)
    }

    @Test
    fun `falls back to native languageName when languageEnglishName is absent`() {
        val dto = HelloAoTranslationDto(
            id = "X", name = "X", englishName = "X", language = "xx", languageName = "Xish"
        )

        assertEquals("Xish", dto.toBibleTranslation().languageName)
    }

    @Test
    fun `numberOfBooks is carried through and marks a 66-book translation as complete`() {
        val dto = HelloAoTranslationDto(
            id = "BSB", name = "Berean Standard Bible", englishName = "Berean Standard Bible",
            language = "eng", numberOfBooks = 66
        )

        val translation = dto.toBibleTranslation()

        assertEquals(66, translation.numberOfBooks)
        assertTrue(translation.isCompleteCanon)
    }

    @Test
    fun `a partial-canon translation (e_g_ NT-only) is not complete`() {
        val dto = HelloAoTranslationDto(
            id = "GHT", name = "NT Portion", englishName = "NT Portion",
            language = "eng", numberOfBooks = 27
        )

        assertFalse(dto.toBibleTranslation().isCompleteCanon)
    }

    // Shapes copied from the live available_translations.json on 2026-10-26 (#98).
    @Test
    fun `licenseUrl and the license terms are read from the catalog JSON`() {
        val dto = catalogEntry(
            "por_blj",
            """, "licenseUrl": "https://ebible.org/Scriptures/details.php?id=porbr2018",
            "license": {"attribution": "required", "commercialUse": "allowed", "derivatives": "allowed",
                        "copyleft": false, "publicDomain": false}"""
        )

        val license = dto.toBibleTranslation().license!!

        assertEquals("https://ebible.org/Scriptures/details.php?id=porbr2018", license.url)
        assertTrue(license.attributionRequired)
        assertFalse(license.publicDomain)
        assertTrue(license.commercialUseAllowed)
        assertNull(license.notice)
    }

    @Test
    fun `a public-domain Bible needs no attribution`() {
        val dto = catalogEntry(
            "spa_r09",
            """, "licenseUrl": "https://ebible.org/Scriptures/details.php?id=spaRV1909",
            "license": {"attribution": "not-required", "commercialUse": "allowed", "derivatives": "allowed",
                        "copyleft": false, "publicDomain": true}"""
        )

        val license = dto.toBibleTranslation().license!!

        assertFalse(license.attributionRequired)
        assertTrue(license.publicDomain)
    }

    @Test
    fun `a non-commercial license and a required notice are kept`() {
        val dto = catalogEntry(
            "eng_net",
            """, "licenseUrl": "https://ebible.org/Scriptures/details.php?id=engnet",
            "licenseNotice": "The Scriptures quoted are from the NET Bible®",
            "license": {"attribution": "required", "commercialUse": "not-allowed", "derivatives": "not-allowed",
                        "copyleft": false, "publicDomain": false}"""
        )

        val license = dto.toBibleTranslation().license!!

        assertFalse(license.commercialUseAllowed)
        assertEquals("The Scriptures quoted are from the NET Bible®", license.notice)
    }

    @Test
    fun `missing license terms are read as the strictest`() {
        val license = catalogEntry("x", """, "licenseUrl": "https://example.org/license"""").toBibleTranslation().license!!

        assertTrue(license.attributionRequired)
        assertFalse(license.publicDomain)
        assertFalse(license.commercialUseAllowed)
    }

    @Test
    fun `no license without an https license page`() {
        assertNull(catalogEntry("x", "").toBibleTranslation().license)
        assertNull(catalogEntry("x", """, "licenseUrl": "http://example.org/license"""").toBibleTranslation().license)
    }
}
