package com.application.bibleapp.utils

import java.text.Normalizer

object TextUtils {

    /**
     * Search's single normalization, applied both to each verse's stored `search_text` and to
     * the query, so the two always agree (#90): lowercase, accents removed in every language
     * ("Graça" -> "graca", "grâce" -> "grace"), and everything that isn't a letter, mark or
     * digit turned into single spaces. Letters of every script are kept, so Korean, Chinese and
     * other non-Latin queries aren't emptied. NFC at the end puts Hangul syllables back together
     * after NFD split them, so "사" doesn't match inside "삭".
     *
     * One pass over the characters instead of regexes: it runs for every verse of a Bible when
     * its `search_text` is filled in, and plain-ASCII text (the whole KJV, nearly) skips the
     * Unicode normalization entirely.
     *
     * Changing this means stored `search_text` no longer matches: bump
     * BibleDatabaseManager.SEARCH_TEXT_VERSION so it's rebuilt.
     */
    fun String.normalizeForSearch(): String {
        val ascii = all { it.code < 0x80 }
        val decomposed = if (ascii) this else Normalizer.normalize(this, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var spacePending = false
        var i = 0
        while (i < decomposed.length) {
            val codePoint = decomposed.codePointAt(i)
            i += Character.charCount(codePoint)
            when (Character.getType(codePoint).toByte()) {
                Character.NON_SPACING_MARK -> Unit // accents, once NFD has split them off
                Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
                Character.MODIFIER_LETTER, Character.OTHER_LETTER,
                Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK,
                Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> {
                    if (spacePending && out.isNotEmpty()) out.append(' ')
                    spacePending = false
                    out.appendCodePoint(foldCase(codePoint))
                }
                else -> spacePending = true
            }
        }
        return if (ascii) out.toString() else Normalizer.normalize(out, Normalizer.Form.NFC)
    }

    /** Lowercase, with Greek's word-final sigma treated as the ordinary one, as capitals have no final form. */
    private fun foldCase(codePoint: Int): Int {
        val lower = Character.toLowerCase(codePoint)
        return if (lower == 'ς'.code) 'σ'.code else lower
    }
}
