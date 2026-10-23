package com.application.bibleapp.data.remote

import com.application.bibleapp.data.model.BUNDLED_TRANSLATION
import com.application.bibleapp.data.model.BibleTranslation
import com.application.bibleapp.data.model.DownloadedVersionInfo
import com.application.bibleapp.data.model.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageGroupTest {

    private fun translation(
        id: String,
        displayName: String,
        languageName: String,
        langCode: String,
        nativeName: String = displayName
    ) = BibleTranslation(
        id = id,
        displayName = displayName,
        nativeName = nativeName,
        languageName = languageName,
        languageCode = langCode,
        textDirection = TextDirection.LTR
    )

    private val sample = listOf(
        translation("KJV", "KJV", "English", "eng"),
        translation("BSB", "BSB", "English", "eng", nativeName = "Berean Standard Bible"),
        translation("GRCBRENT", "Brenton", "Ancient Greek", "grc"),
        translation("ARBNAV", "NAV", "Arabic", "arb", nativeName = "كتاب الحياة")
    )

    @Test
    fun `groups are sorted alphabetically by language name`() {
        val groups = groupVersionsByLanguage(sample)

        assertEquals(listOf("Ancient Greek", "Arabic", "English"), groups.map { it.languageName })
    }

    @Test
    fun `translations within a group are sorted by display name`() {
        val groups = groupVersionsByLanguage(sample)
        val english = groups.first { it.languageName == "English" }

        assertEquals(listOf("BSB", "KJV"), english.translations.map { it.displayName })
    }

    @Test
    fun `every translation ends up in exactly one group`() {
        val groups = groupVersionsByLanguage(sample)

        assertEquals(sample.size, groups.sumOf { it.translations.size })
    }

    @Test
    fun `search matches language name case-insensitively`() {
        val groups = groupVersionsByLanguage(sample, query = "greek")

        assertEquals(listOf("Ancient Greek"), groups.map { it.languageName })
    }

    @Test
    fun `search matches display name and native name`() {
        val byDisplayName = groupVersionsByLanguage(sample, query = "kjv")
        val byNativeName = groupVersionsByLanguage(sample, query = "berean")

        assertEquals(listOf("KJV"), byDisplayName.flatMap { it.translations }.map { it.displayName })
        assertEquals(listOf("BSB"), byNativeName.flatMap { it.translations }.map { it.displayName })
    }

    @Test
    fun `search with no matches returns no groups`() {
        val groups = groupVersionsByLanguage(sample, query = "klingon")

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `blank query returns everything ungrouped by filter`() {
        val groups = groupVersionsByLanguage(sample, query = "   ")

        assertEquals(sample.size, groups.sumOf { it.translations.size })
    }

    // ---- The bundled KJV and the "Downloaded" section (#56, #63) ----

    private val catalog = listOf(
        translation("BSB", "BSB", "English", "eng", nativeName = "Berean Standard Bible"),
        translation("spa_pdt", "PDT", "Spanish", "spa"),
        translation("twi_asa", "ASA", "Twi", "twi")
    )

    private fun downloaded(id: String, name: String = "") = id to DownloadedVersionInfo(id, schemaVersion = 5, name = name)

    @Test
    fun `bundled KJV is found by searching KJV or King James`() {
        val withKjv = withBundledTranslation(catalog)

        assertEquals(listOf("kjv"), groupVersionsByLanguage(withKjv, "KJV").flatMap { it.translations }.map { it.id })
        assertEquals(listOf("kjv"), groupVersionsByLanguage(withKjv, "king james").flatMap { it.translations }.map { it.id })
    }

    @Test
    fun `bundled KJV is not added twice if the catalog ever lists it`() {
        val withKjv = withBundledTranslation(catalog + BUNDLED_TRANSLATION)

        assertEquals(1, withKjv.count { it.id == BUNDLED_TRANSLATION.id })
    }

    @Test
    fun `downloaded section starts with the bundled KJV even with nothing downloaded`() {
        val section = downloadedSection(groupVersionsByLanguage(withBundledTranslation(catalog)), emptyMap())

        assertEquals(listOf("kjv"), section.map { it.id })
    }

    @Test
    fun `downloaded section lists catalog downloads in group order after the bundled KJV`() {
        val groups = groupVersionsByLanguage(withBundledTranslation(catalog))
        val section = downloadedSection(groups, mapOf(downloaded("twi_asa"), downloaded("spa_pdt")))

        assertEquals(listOf("kjv", "spa_pdt", "twi_asa"), section.map { it.id })
    }

    @Test
    fun `a download the catalog no longer lists still shows, under its stored name`() {
        val groups = groupVersionsByLanguage(withBundledTranslation(catalog))
        val section = downloadedSection(groups, mapOf(downloaded("spa_pdt"), downloaded("es-pddpt", "Palabra de Dios para ti")))

        assertEquals(listOf("kjv", "spa_pdt", "es-pddpt"), section.map { it.id })
        assertEquals("Palabra de Dios para ti", section.last().displayName)
    }

    @Test
    fun `an unlisted download with no stored name falls back to its id`() {
        val section = downloadedSection(emptyList(), mapOf(downloaded("old-id")))

        assertEquals("old-id", section.last().displayName)
    }

    @Test
    fun `with no catalog (offline), every download still shows`() {
        val section = downloadedSection(emptyList(), mapOf(downloaded("twi_asa", "Asante Twi"), downloaded("spa_pdt", "Palabra de Dios")))

        assertEquals(listOf("kjv", "twi_asa", "spa_pdt"), section.map { it.id })
    }
}
