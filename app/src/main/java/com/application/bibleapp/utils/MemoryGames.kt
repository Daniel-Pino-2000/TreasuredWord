package com.application.bibleapp.utils

import com.application.bibleapp.utils.TextUtils.normalizeForSearch
import kotlin.math.ceil
import kotlin.random.Random

/*
 * The text side of the Memorize games (#363): cutting a passage into words and phrases, the
 * gaps, the fading order, and checking a typed verse. No Android, so it's unit-tested
 * (MemoryGamesTest) in English, Spanish, Korean and Chinese.
 */

/**
 * One word of a passage as the games see it. [core] is what's played with ("loved"), and
 * [leading]/[trailing] the punctuation around it ("“", ","); [key] is [core] the way answers are
 * compared: lowercase, no accents or punctuation (search's normalization, #90). In a script
 * written without spaces (Chinese), each character is a word, and [spaceBefore] is false.
 */
data class PracticeWord(
    val leading: String,
    val core: String,
    val trailing: String,
    val spaceBefore: Boolean
) {
    val key: String = core.normalizeForSearch().replace(" ", "")
    val text: String get() = leading + core + trailing

    /** Ends a phrase: a comma, a full stop or the like comes after it. */
    val endsPhrase: Boolean get() = trailing.any { it in PHRASE_ENDS }

    /** Written without spaces around it, like a Chinese character. */
    val isCharacter: Boolean get() = core.isNotEmpty() && core.codePointCount(0, core.length) == 1 && isNoSpaceScript(core.codePointAt(0))
}

private const val PHRASE_ENDS = ",;:.!?，。；：、！？"

private fun isNoSpaceScript(codePoint: Int): Boolean = Character.UnicodeScript.of(codePoint) in NO_SPACE_SCRIPTS

private val NO_SPACE_SCRIPTS = setOf(
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER,
    Character.UnicodeScript.MYANMAR
)

private fun isWordChar(codePoint: Int): Boolean = Character.isLetterOrDigit(codePoint) ||
    Character.getType(codePoint).toByte().let { it == Character.NON_SPACING_MARK || it == Character.COMBINING_SPACING_MARK }

/** Joins words back into text, with spaces only where the language has them. */
fun List<PracticeWord>.joinWords(transform: (PracticeWord) -> String = { it.text }): String {
    val out = StringBuilder()
    forEachIndexed { index, word ->
        if (index > 0 && word.spaceBefore) out.append(' ')
        out.append(transform(word))
    }
    return out.toString()
}

/**
 * Cuts [text] into words. Spaces and dashes separate words; punctuation sticks to the word next
 * to it ("world," / "“For"); a piece with no letters at all (a lone dash) joins the word before
 * it. Characters of a script written without spaces are a word each, so Chinese is split by
 * character, while Korean, which has spaces, keeps its words whole.
 */
fun practiceWords(text: String): List<PracticeWord> {
    val words = mutableListOf<PracticeWord>()
    var pendingLeading = ""
    var spaceBefore = false

    fun addPiece(piece: String, space: Boolean) {
        if (piece.isEmpty()) return
        val start = piece.indexOfFirstCodePoint(::isWordChar)
        if (start < 0) {
            // No letters: punctuation of its own, like "—" between spaces.
            if (words.isNotEmpty()) {
                val last = words.removeAt(words.lastIndex)
                words += last.copy(trailing = last.trailing + (if (space) " " else "") + piece)
            } else {
                pendingLeading += piece
            }
            return
        }
        val end = piece.indexOfLastCodePointEnd(::isWordChar)
        val leading = pendingLeading + piece.substring(0, start)
        pendingLeading = ""
        val core = piece.substring(start, end)
        val trailing = piece.substring(end)
        // A run of Chinese characters (maybe with punctuation between them) is cut per character.
        if (core.codePoints().anyMatch(::isNoSpaceScript)) {
            var i = 0
            var lead = leading
            var first = true
            while (i < core.length) {
                val cp = core.codePointAt(i)
                val next = i + Character.charCount(cp)
                if (isWordChar(cp)) {
                    if (isNoSpaceScript(cp)) {
                        words += PracticeWord(lead, core.substring(i, next), "", spaceBefore = first && space && words.isNotEmpty() && !words.last().isCharacter)
                        lead = ""
                        i = next
                    } else {
                        // A Latin word or number inside Chinese text stays whole.
                        var j = next
                        while (j < core.length && isWordChar(core.codePointAt(j)) && !isNoSpaceScript(core.codePointAt(j))) {
                            j += Character.charCount(core.codePointAt(j))
                        }
                        words += PracticeWord(lead, core.substring(i, j), "", spaceBefore = first && space && words.isNotEmpty())
                        lead = ""
                        i = j
                    }
                    first = false
                } else {
                    // Punctuation between characters ends the word before it.
                    val last = words.removeAt(words.lastIndex)
                    words += last.copy(trailing = last.trailing + core.substring(i, next))
                    i = next
                }
            }
            if (trailing.isNotEmpty()) {
                val last = words.removeAt(words.lastIndex)
                words += last.copy(trailing = last.trailing + trailing)
            }
        } else {
            words += PracticeWord(leading, core, trailing, spaceBefore = space && words.isNotEmpty())
        }
    }

    // Split on whitespace, and after dashes, which often join two words with no space ("Lord—and").
    val piece = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.isWhitespace() -> {
                addPiece(piece.toString(), spaceBefore)
                piece.clear()
                spaceBefore = true
            }
            c == '—' || c == '–' -> {
                piece.append(c)
                addPiece(piece.toString(), spaceBefore)
                piece.clear()
                spaceBefore = false
            }
            else -> piece.append(c)
        }
        i++
    }
    addPiece(piece.toString(), spaceBefore)
    return words
}

private inline fun String.indexOfFirstCodePoint(predicate: (Int) -> Boolean): Int {
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        if (predicate(cp)) return i
        i += Character.charCount(cp)
    }
    return -1
}

private inline fun String.indexOfLastCodePointEnd(predicate: (Int) -> Boolean): Int {
    var i = length
    while (i > 0) {
        val cp = codePointBefore(i)
        if (predicate(cp)) return i
        i -= Character.charCount(cp)
    }
    return -1
}

/** Words that can be played with: with letters or digits, which is all of them but a stray mark. */
private fun List<PracticeWord>.playable(): List<Int> = indices.filter { this[it].key.isNotEmpty() }

/**
 * "Fill the gaps": about one word in four, at random, preferring words longer than one letter
 * ("a" and "I" make poor gaps). In the order they appear.
 */
fun pickGaps(words: List<PracticeWord>, random: Random): List<Int> {
    val playable = words.playable()
    if (playable.isEmpty()) return emptyList()
    val count = (playable.size / 4.0).let { kotlin.math.round(it).toInt() }.coerceIn(1, playable.size)
    val preferred = playable.filter { words[it].isCharacter || words[it].key.length > 1 }.shuffled(random)
    val rest = (playable - preferred.toSet()).shuffled(random)
    return (preferred + rest).take(count).sorted()
}

/**
 * The word bank for [gaps]: each missing word once per gap, plus up to [decoys] other words of the
 * passage that aren't the answer to any gap, shuffled. Holds the indices of the words.
 */
fun wordBank(words: List<PracticeWord>, gaps: List<Int>, random: Random, decoys: Int = 3): List<Int> {
    val answerKeys = gaps.mapTo(HashSet()) { words[it].key }
    val decoyIndices = words.playable()
        .filter { it !in gaps && words[it].key !in answerKeys }
        .distinctBy { words[it].key }
        .shuffled(random)
        .take(decoys)
    return (gaps + decoyIndices).shuffled(random)
}

/**
 * "Put it in order": the passage cut into phrases at its commas and full stops, long ones cut
 * again, so there are a few pieces to order (at most about eight). Each phrase is a range of
 * word indices. A passage of few words is ordered word by word.
 */
fun splitPhrases(words: List<PracticeWord>): List<IntRange> {
    if (words.isEmpty()) return emptyList()
    val characters = words.count { it.isCharacter } * 2 > words.size
    val maxLength = maxOf(if (characters) 6 else 5, ceil(words.size / 8.0).toInt())
    val phrases = mutableListOf<IntRange>()
    var start = 0
    words.forEachIndexed { index, word ->
        val length = index - start + 1
        if (word.endsPhrase || length >= maxLength || index == words.lastIndex) {
            phrases += start..index
            start = index + 1
        }
    }
    // Too few pieces to be a game: cut by words instead.
    if (phrases.size < 3 && words.size >= 3) {
        val size = ceil(words.size / 3.0).toInt().coerceAtLeast(1)
        return words.indices.chunked(size).map { it.first()..it.last() }
    }
    return phrases
}

/** [count] items in an order that isn't the right one (when there are two or more). */
fun shuffledOrder(count: Int, random: Random): List<Int> {
    val order = (0 until count).toList()
    if (count < 2) return order
    var shuffled: List<Int>
    do { shuffled = order.shuffled(random) } while (shuffled == order)
    return shuffled
}

/**
 * "Fading words": the order words disappear in, over [FADING_STEPS] taps. Step k hides the first
 * [fadingHiddenCount] of these.
 */
fun fadingOrder(words: List<PracticeWord>, random: Random): List<Int> = words.playable().shuffled(random)

const val FADING_STEPS = 4

fun fadingHiddenCount(total: Int, step: Int): Int = ceil(total * step.coerceIn(0, FADING_STEPS) / FADING_STEPS.toDouble()).toInt()

/**
 * "First letters": each word cut to its first letter, keeping its punctuation ("F G s l t w,").
 * Chinese, where a word is one character, shows each phrase's first character and a circle for
 * the rest, which is how the method is used there.
 */
fun firstLetterHint(words: List<PracticeWord>, index: Int): String {
    val word = words[index]
    if (word.key.isEmpty()) return word.text
    if (word.isCharacter) {
        val startsPhrase = index == 0 || words[index - 1].endsPhrase
        return word.leading + (if (startsPhrase) word.core else "○") + word.trailing
    }
    val first = word.core.substring(0, word.core.offsetByCodePoints(0, 1))
    return word.leading + first + word.trailing
}

/** At least this share of first tries right passes the tapping games. */
const val TAP_GAME_PASS_SHARE = 0.75

/** Whether a tapping game passes: [firstTryRight] of [total] answers right the first time. */
fun tapGamePassed(firstTryRight: Int, total: Int): Boolean = total == 0 || firstTryRight >= ceil(total * TAP_GAME_PASS_SHARE)

/** How a word of the passage came out in "Type it from memory". */
enum class TypedMark { RIGHT, TYPO, MISSED, WRONG }

/**
 * The typed answer checked against the passage: a mark for each of its words, and how many extra
 * words were typed. [score] is the share right, a typo counting as right while there are no more
 * than one per ten words; 90% or more passes.
 */
data class TypedCheck(val marks: List<TypedMark>, val extraWords: Int) {
    val right: Int get() = marks.count { it == TypedMark.RIGHT || it == TypedMark.TYPO }
    val score: Double get() = if (marks.isEmpty()) 0.0 else right.toDouble() / (marks.size + extraWords)
    val passed: Boolean get() = marks.isNotEmpty() && score >= TYPED_PASS_SCORE
}

const val TYPED_PASS_SCORE = 0.9

/** Typos forgiven: one for every ten words, rounded up. */
fun allowedTypos(wordCount: Int): Int = ceil(wordCount / 10.0).toInt()

/**
 * Lines the typed words up with the passage's (an edit distance over words, so one missed word
 * doesn't make every word after it wrong) and marks each passage word. Case, punctuation, accents
 * and spaces don't matter. A word one letter off (three letters or more) is a typo.
 */
fun checkTypedVerse(passage: List<PracticeWord>, typed: String): TypedCheck {
    val target = passage.filter { it.key.isNotEmpty() }.map { it.key }
    val answer = practiceWords(typed).map { it.key }.filter { it.isNotEmpty() }
    val n = target.size
    val m = answer.size
    // cost[i][j]: aligning target[i..] with answer[j..].
    val cost = Array(n + 1) { DoubleArray(m + 1) }
    for (i in n downTo 0) for (j in m downTo 0) {
        cost[i][j] = when {
            i == n -> (m - j).toDouble()
            j == m -> (n - i).toDouble()
            else -> minOf(
                pairCost(target[i], answer[j]) + cost[i + 1][j + 1],
                1.0 + cost[i + 1][j], // missed
                1.0 + cost[i][j + 1]  // extra
            )
        }
    }
    val marks = ArrayList<TypedMark>(n)
    var extra = 0
    var i = 0
    var j = 0
    while (i < n || j < m) {
        when {
            i < n && j < m && cost[i][j] == pairCost(target[i], answer[j]) + cost[i + 1][j + 1] -> {
                marks += when {
                    target[i] == answer[j] -> TypedMark.RIGHT
                    isTypo(target[i], answer[j]) -> TypedMark.TYPO
                    else -> TypedMark.WRONG
                }
                i++; j++
            }
            i < n && cost[i][j] == 1.0 + cost[i + 1][j] -> { marks += TypedMark.MISSED; i++ }
            else -> { extra++; j++ }
        }
    }
    // Typos past the allowance count as wrong.
    var typosLeft = allowedTypos(n)
    val forgiven = marks.map { mark ->
        if (mark != TypedMark.TYPO) mark else if (typosLeft-- > 0) TypedMark.TYPO else TypedMark.WRONG
    }
    return TypedCheck(forgiven, extra)
}

/** A typo costs a little, so an exact match elsewhere is preferred when lining words up. */
private fun pairCost(a: String, b: String): Double = when {
    a == b -> 0.0
    isTypo(a, b) -> 0.25
    else -> 1.0
}

private fun isTypo(a: String, b: String): Boolean =
    a.length >= 3 && b.length >= 3 && kotlin.math.abs(a.length - b.length) <= 1 && editDistance(a, b) == 1

/** Levenshtein distance, with a swap of two neighbouring letters ("teh") counting as one. */
internal fun editDistance(a: String, b: String): Int {
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) for (j in 1..b.length) {
        val same = if (a[i - 1] == b[j - 1]) 0 else 1
        d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + same)
        if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
    }
    return d[a.length][b.length]
}
