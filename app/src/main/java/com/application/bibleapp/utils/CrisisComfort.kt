package com.application.bibleapp.utils

/**
 * The verses of comfort a crisis answer chooses from (#282), the same list as the server's
 * instructions (system_prompt.md section 6) and its evaluation (EvalChecks.COMFORT_VERSES): keep
 * the three in step. Psalm 34:18 first, as the one shown when the answer cites none of them.
 */
val CRISIS_COMFORT_VERSES: List<VerseReference> = listOf(
    VerseReference(bookId = 19, chapter = 34, startVerse = 18), // despair, wanting to die, self-harm
    VerseReference(bookId = 19, chapter = 147, startVerse = 3), // grief, a broken heart
    VerseReference(bookId = 19, chapter = 46, startVerse = 1), // danger, or someone else in danger
    VerseReference(bookId = 23, chapter = 41, startVerse = 10), // fear
    VerseReference(bookId = 19, chapter = 9, startVerse = 9), // abuse
    VerseReference(bookId = 40, chapter = 11, startVerse = 28), // exhaustion, carrying too much
    VerseReference(bookId = 45, chapter = 8, startVerse = 38, endVerse = 39), // unloved, alone, worthless
    VerseReference(bookId = 25, chapter = 3, startVerse = 22, endVerse = 23) // hopelessness, guilt
)

/**
 * The verse the crisis help card shows (#282): the first one the answer [cited] from
 * [CRISIS_COMFORT_VERSES], which the instructions ask the AI to choose for the person's
 * situation, or Psalm 34:18 when it cited none of them (or wrote only the crisis marker).
 */
fun crisisComfortVerse(cited: List<VerseReference>): VerseReference =
    cited.firstOrNull { it in CRISIS_COMFORT_VERSES } ?: CRISIS_COMFORT_VERSES.first()
