package com.application.bibleapp.viewmodel

import com.application.bibleapp.data.model.BibleTranslation
import com.application.bibleapp.data.model.DownloadedVersionInfo
import com.application.bibleapp.data.model.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class VersionLabelTest {

    private fun translation(id: String, displayName: String) = BibleTranslation(
        id = id,
        displayName = displayName,
        nativeName = displayName,
        languageName = "Twi",
        languageCode = "twi",
        textDirection = TextDirection.LTR
    )

    private val catalog = listOf(translation("twi_asa", "ASA"))
    private val downloaded = mapOf("twi_asa" to DownloadedVersionInfo("twi_asa", schemaVersion = 5, name = "ASA"))

    @Test
    fun `the catalog name wins when the catalog has loaded`() {
        val renamed = mapOf("twi_asa" to DownloadedVersionInfo("twi_asa", schemaVersion = 5, name = "OLD"))
        assertEquals("ASA", versionLabelFor("twi_asa", catalog, renamed))
    }

    @Test
    fun `without the catalog, the name stored with the download is used`() {
        assertEquals("ASA", versionLabelFor("twi_asa", emptyList(), downloaded))
    }

    @Test
    fun `a download with no stored name falls back to the uppercased id`() {
        val unnamed = mapOf("twi_asa" to DownloadedVersionInfo("twi_asa", schemaVersion = 5))
        assertEquals("TWI_ASA", versionLabelFor("twi_asa", emptyList(), unnamed))
        assertEquals("TWI_ASA", versionLabelFor("twi_asa", emptyList(), emptyMap()))
    }

    @Test
    fun `the bundled KJV is always KJV`() {
        assertEquals("KJV", versionLabelFor("kjv", emptyList(), emptyMap()))
    }
}
