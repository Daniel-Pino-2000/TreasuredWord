package com.application.bibleapp.utils

import com.application.bibleapp.utils.TextUtils.normalizeForSearch

/**
 * Phrases someone in danger might type into Search (#330), so the help card still comes first when
 * search by meaning can't ask the server (offline, or out of neurons for the day). Online, the
 * server's crisis guide decides (#324) and this list isn't used.
 *
 * Written by hand, not machine-translated, and every phrase reviewed by the developer: a wrong or
 * missing one matters here. Kept short and specific on purpose: "want to die", never "die" alone,
 * which fills the Bible's own text, and "kill myself", not "kill me" ("they seek to kill me").
 * A few false positives are accepted (Jonah's "I want to die" in some translations); the card is a
 * kind word and a helpline, never a block.
 *
 * Every list is checked whatever the app's language, as people often type in another one. Phrases
 * are written as people type them, with accents and capitals: they go through the same
 * [normalizeForSearch] as the query. Leave out apostrophes ("dont want to live").
 */
object CrisisPhrases {

    /** By language tag, as the app's `values-*` folders name them. */
    val PHRASES: Map<String, List<String>> = mapOf(
        "en" to listOf(
            "want to die", "wanna die", "wish i was dead", "wish i were dead", "want to be dead",
            "kill myself", "killing myself", "end my life", "ending my life", "take my own life",
            "end it all", "dont want to live", "do not want to live", "dont want to be alive",
            "no reason to live", "better off dead", "suicide", "suicidal",
            "hurt myself", "harm myself", "self harm", "cutting myself"
        ),
        "de" to listOf(
            "will sterben", "möchte sterben", "will nicht mehr leben", "möchte nicht mehr leben",
            "keine lust mehr zu leben", "mich umbringen", "bringe mich um", "mir das leben nehmen",
            "mein leben beenden", "lieber tot", "selbstmord", "suizid", "suizidal",
            "mich selbst verletzen", "ritze mich"
        ),
        "es" to listOf(
            "quiero morir", "quiero morirme", "me quiero morir", "deseo morir", "quisiera morir",
            "quisiera morirme", "quiero estar muerto", "quiero estar muerta", "no quiero vivir",
            "quiero matarme", "me quiero matar", "voy a matarme", "me voy a matar", "suicidarme",
            "suicidio", "suicida", "quitarme la vida", "acabar con mi vida", "terminar con mi vida",
            "autolesión", "autolesiones"
        ),
        "fr" to listOf(
            "veux mourir", "envie de mourir", "veux me tuer", "vais me tuer", "envie de me tuer",
            "me suicider", "suicide", "suicidaire", "suicidaires", "mettre fin à mes jours", "mettre fin à ma vie",
            "en finir avec la vie", "veux en finir", "veux plus vivre", "plus envie de vivre",
            "automutilation", "me scarifier"
        ),
        "pt" to listOf(
            "quero morrer", "quero estar morto", "quero estar morta", "não quero mais viver",
            "não quero viver", "quero me matar", "vou me matar", "penso em me matar", "me suicidar",
            "suicídio", "suicida", "tirar minha vida", "tirar a minha vida", "tirar a própria vida",
            "acabar com minha vida", "acabar com a minha vida", "automutilação"
        ),
        "in" to listOf(
            "ingin mati", "pengen mati", "pingin mati", "bunuh diri", "membunuh diri",
            "mengakhiri hidup", "tidak ingin hidup lagi", "tak ingin hidup lagi", "tidak mau hidup lagi",
            "gak mau hidup lagi", "nggak mau hidup lagi", "menyakiti diri sendiri", "melukai diri sendiri"
        ),
        "fil" to listOf(
            "gusto ko nang mamatay", "gusto ko na mamatay", "gusto kong mamatay",
            "mamatay na lang ako", "ayoko nang mabuhay", "ayaw ko nang mabuhay", "ayoko na mabuhay",
            "magpakamatay", "nagpakamatay", "pagpapakamatay", "wakasan ang buhay ko",
            "tapusin ang buhay ko", "saktan ang sarili ko"
        ),
        "sw" to listOf(
            "nataka kufa", "ninataka kufa", "natamani kufa", "nataka kujiua", "kujiua", "kujitoa uhai",
            "sitaki kuishi", "sitaki kuendelea kuishi", "kujidhuru"
        ),
        "ko" to listOf(
            "죽고 싶", "죽어 버리고 싶", "살고 싶지 않", "자살", "목숨을 끊", "스스로 목숨", "극단적 선택", "자해"
        ),
        "zh-Hans" to listOf(
            "想死", "不想活", "活不下去", "自杀", "轻生", "寻死", "结束自己的生命", "结束我的生命",
            "了结自己", "自残", "割腕"
        )
    )

    /**
     * Wordings that contain a phrase above but mean the opposite, or something else: "I don't
     * want to die", Romans 6's "die to self", Chinese 想死你了 ("I miss you so much"). Taken out of
     * the query before matching, so "I don't want to die, I want to kill myself" still matches.
     */
    val NOT_CRISIS: List<String> = listOf(
        "dont want to die", "do not want to die", "never want to die",
        "die to self", "die to myself", "die to sin", "die to the flesh", "die to my flesh",
        "no quiero morir", "no quiero morirme", "no me quiero morir",
        "pas envie de mourir",
        "não quero morrer",
        "tidak ingin mati", "tak ingin mati", "gak pengen mati", "nggak pengen mati", "tidak pengen mati",
        "죽고 싶지 않",
        "不想死", "想死你", "想死我了"
    )

    private val phrases: List<Phrase> = PHRASES.values.flatten().distinct().mapNotNull(Phrase::of)
    private val exclusions: List<Phrase> = NOT_CRISIS.mapNotNull(Phrase::of)

    /** Whether [query] holds one of [PHRASES], once [NOT_CRISIS] is taken out. */
    fun matches(query: String): Boolean {
        val normalized = query.withoutApostrophes().normalizeForSearch()
        // Words with a space on each side, and the whole text without spaces (see [Phrase]).
        var spaced = " $normalized "
        var unspaced = normalized.replace(" ", "")
        exclusions.forEach {
            if (it.anywhere) unspaced = unspaced.replace(it.text, " ") else spaced = spaced.replace(" ${it.text} ", "  ")
        }
        return phrases.any { if (it.anywhere) it.text in unspaced else " ${it.text} " in spaced }
    }

    /**
     * "don't" as "dont", the way the lists write it, but French "l'envie" or "n'ai" as two words:
     * an apostrophe after a one- or two-letter word, or one ending in "qu", is a space.
     */
    private fun String.withoutApostrophes(): String =
        replace(ELISION, "$1 ").replace(APOSTROPHES, "")

    private const val APOSTROPHE_CHARS = "'’ʼ`´"
    private val APOSTROPHES = Regex("[$APOSTROPHE_CHARS]")
    private val ELISION = Regex("(?<!\\p{L})(\\p{L}{1,2}|\\p{L}*qu)[$APOSTROPHE_CHARS]", RegexOption.IGNORE_CASE)

    /**
     * A normalized phrase. Hangul and Chinese ones are matched anywhere, with spaces ignored
     * ([anywhere]): Korean endings and particles follow the stem (죽고 싶어요), people often leave
     * the spaces out (죽고싶어), and Chinese has none. Others must be whole words, so "want to die"
     * doesn't match "want to diet".
     */
    private class Phrase(val text: String, val anywhere: Boolean) {
        companion object {
            fun of(raw: String): Phrase? {
                val normalized = raw.normalizeForSearch()
                if (normalized.isEmpty()) return null
                val anywhere = normalized.codePoints().anyMatch {
                    val script = Character.UnicodeScript.of(it)
                    script == Character.UnicodeScript.HANGUL || script == Character.UnicodeScript.HAN
                }
                return Phrase(if (anywhere) normalized.replace(" ", "") else normalized, anywhere)
            }
        }
    }
}
