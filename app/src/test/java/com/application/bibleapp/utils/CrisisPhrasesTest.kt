package com.application.bibleapp.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's own crisis phrases for Search's help card offline (#330). */
class CrisisPhrasesTest {

    private fun assertCrisis(vararg queries: String) = queries.forEach { assertTrue("\"$it\" should match", CrisisPhrases.matches(it)) }

    private fun assertNotCrisis(vararg queries: String) = queries.forEach { assertFalse("\"$it\" shouldn't match", CrisisPhrases.matches(it)) }

    @Test
    fun `every language has its phrases`() {
        assertEquals(setOf("en", "de", "es", "fr", "pt", "in", "fil", "sw", "ko", "zh-Hans"), CrisisPhrases.PHRASES.keys)
        CrisisPhrases.PHRASES.forEach { (language, phrases) -> assertTrue(language, phrases.isNotEmpty()) }
    }

    @Test
    fun `every phrase matches itself, and none is taken out as a near-miss`() {
        CrisisPhrases.PHRASES.values.flatten().forEach { assertCrisis(it, it.uppercase(), "  $it?! ") }
    }

    @Test
    fun `no phrase is one of the Bible's everyday words`() {
        // "die", "death", "kill" fill the Bible's own text (#330): never a phrase alone.
        assertNotCrisis(
            "die", "dead", "death", "kill", "life", "blood", "sterben", "morir", "muerte", "mourir", "morrer",
            "mati", "mamatay", "kufa", "죽음", "죽다", "死", "死亡"
        )
    }

    @Test
    fun english() {
        assertCrisis(
            "I want to die", "i just WANT TO DIE", "I'm going to kill myself", "thinking about killing myself",
            "how do I end my life", "I don't want to live anymore", "I dont want to live", "suicide",
            "Would God forgive suicide?", "I feel suicidal", "I keep cutting myself", "I hurt myself on purpose",
            "everyone would be better off dead without me", "I wish I was dead"
        )
        assertNotCrisis(
            "I want to diet", "I don't want to die", "I do not want to die", "I want to die to self",
            "how to die to sin", "they seek to kill me", "a time to die", "the wages of sin is death",
            "eternal life", "my life", "live by faith"
        )
    }

    @Test
    fun `don't want to die, but still a crisis`() {
        assertCrisis("I don't want to die but I want to kill myself")
    }

    @Test
    fun german() {
        assertCrisis(
            "Ich will sterben", "ich möchte sterben", "Ich mochte sterben", "ich will nicht mehr leben",
            "ich will mich umbringen", "Selbstmord", "Ich denke an Suizid", "ich wäre lieber tot"
        )
        assertNotCrisis("ich will nicht sterben", "Leben und Sterben", "sie wollen mich töten", "ewiges Leben")
    }

    @Test
    fun spanish() {
        assertCrisis(
            "quiero morir", "Quiero morirme", "me quiero morir", "No quiero vivir más", "quiero matarme",
            "pienso en el suicidio", "quiero quitarme la vida", "acabar con mi vida"
        )
        assertNotCrisis("no quiero morir", "No me quiero morir", "buscan matarme", "vida eterna", "la muerte")
    }

    @Test
    fun french() {
        assertCrisis(
            "Je veux mourir", "j'ai envie de mourir", "je vais me tuer", "je veux me suicider",
            "je ne veux plus vivre", "mettre fin à mes jours", "pensées suicidaires", "je n'ai plus envie de vivre",
            "j’ai l’envie de mourir", "jusqu'à vouloir me suicider"
        )
        assertNotCrisis("je n'ai pas envie de mourir", "je ne veux pas mourir", "ils veulent me tuer", "la vie éternelle")
    }

    @Test
    fun portuguese() {
        assertCrisis(
            "Eu quero morrer", "não quero mais viver", "nao quero viver", "quero me matar", "vou me matar",
            "suicídio", "tirar a minha vida"
        )
        assertNotCrisis("não quero morrer", "querem me matar", "vida eterna", "a morte")
    }

    @Test
    fun indonesian() {
        assertCrisis(
            "Aku ingin mati", "pengen mati aja", "aku mau bunuh diri", "ingin mengakhiri hidup",
            "saya tidak ingin hidup lagi", "menyakiti diri sendiri"
        )
        assertNotCrisis("aku tidak ingin mati", "hidup kekal", "kematian", "lampu mau mati")
    }

    @Test
    fun filipino() {
        assertCrisis(
            "Gusto ko nang mamatay", "gusto kong mamatay", "ayoko nang mabuhay", "gusto kong magpakamatay",
            "sana mamatay na lang ako", "pagpapakamatay"
        )
        assertNotCrisis("ayokong mamatay", "buhay na walang hanggan", "kamatayan")
    }

    @Test
    fun swahili() {
        assertCrisis("Nataka kufa", "nataka kujiua", "sitaki kuishi tena", "anataka kujiua")
        assertNotCrisis("sitaki kufa", "uzima wa milele", "mauti")
    }

    @Test
    fun korean() {
        assertCrisis("죽고 싶어요", "죽고싶다", "너무 죽고 싶어", "살고 싶지 않아요", "자살", "자살하고 싶어", "목숨을 끊고 싶다", "자해")
        assertNotCrisis("죽고 싶지 않아요", "죽고싶지않아", "영생", "죽음", "사랑")
    }

    @Test
    fun chinese() {
        assertCrisis("我想死", "我不想活了", "活不下去了", "想自杀", "我想自殺", "結束自己的生命", "自残")
        assertNotCrisis("我不想死", "想死你了", "永生", "死亡", "生命")
    }

    @Test
    fun `a phrase in another language than the app's still matches`() {
        // People often type in a language other than the app's: every list is always checked.
        assertCrisis("I want to die. Quiero morir.", "Taglish: gusto ko nang mamatay")
    }
}
