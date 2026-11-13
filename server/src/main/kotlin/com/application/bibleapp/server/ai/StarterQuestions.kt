package com.application.bibleapp.server.ai

/**
 * The Ask tab's starter questions (#284), worded as the app shows them in each language
 * (`ai_starter_*` in the app's strings.xml files; keep them in step). A request names a starter by
 * its id, and the server asks this wording, never the request's text, so the cache of starter
 * answers can only ever hold answers to these questions.
 */
object StarterQuestions {
    val ids: Set<String> = setOf("saved", "baptism", "trinity", "prayer", "start_reading", "death")

    private val byLanguage: Map<String, Map<String, String>> = mapOf(
        "en" to mapOf(
            "saved" to "How can I be saved?",
            "baptism" to "What does the Bible say about baptism?",
            "trinity" to "What is the Trinity?",
            "prayer" to "How should I pray?",
            "start_reading" to "Where should I start reading the Bible?",
            "death" to "What happens when we die?"
        ),
        "de" to mapOf(
            "saved" to "Wie kann ich gerettet werden?",
            "baptism" to "Was sagt die Bibel über die Taufe?",
            "trinity" to "Was ist die Dreieinigkeit?",
            "prayer" to "Wie soll ich beten?",
            "start_reading" to "Wo fange ich am besten mit dem Bibellesen an?",
            "death" to "Was passiert, wenn wir sterben?"
        ),
        "es" to mapOf(
            "saved" to "¿Cómo puedo ser salvo?",
            "baptism" to "¿Qué dice la Biblia sobre el bautismo?",
            "trinity" to "¿Qué es la Trinidad?",
            "prayer" to "¿Cómo debo orar?",
            "start_reading" to "¿Por dónde empiezo a leer la Biblia?",
            "death" to "¿Qué pasa cuando morimos?"
        ),
        "fr" to mapOf(
            "saved" to "Comment puis-je être sauvé ?",
            "baptism" to "Que dit la Bible sur le baptême ?",
            "trinity" to "Qu'est-ce que la Trinité ?",
            "prayer" to "Comment devrais-je prier ?",
            "start_reading" to "Par où commencer la lecture de la Bible ?",
            "death" to "Que se passe-t-il quand nous mourons ?"
        ),
        "pt-BR" to mapOf(
            "saved" to "Como posso ser salvo?",
            "baptism" to "O que a Bíblia diz sobre o batismo?",
            "trinity" to "O que é a Trindade?",
            "prayer" to "Como devo orar?",
            "start_reading" to "Por onde começo a ler a Bíblia?",
            "death" to "O que acontece quando morremos?"
        ),
        "id" to mapOf(
            "saved" to "Bagaimana saya bisa diselamatkan?",
            "baptism" to "Apa kata Alkitab tentang baptisan?",
            "trinity" to "Apa itu Tritunggal?",
            "prayer" to "Bagaimana seharusnya saya berdoa?",
            "start_reading" to "Dari mana sebaiknya saya mulai membaca Alkitab?",
            "death" to "Apa yang terjadi saat kita meninggal?"
        ),
        "fil" to mapOf(
            "saved" to "Paano ako maliligtas?",
            "baptism" to "Ano ang sinasabi ng Bibliya tungkol sa bautismo?",
            "trinity" to "Ano ang Trinidad?",
            "prayer" to "Paano ako dapat manalangin?",
            "start_reading" to "Saan ako dapat magsimulang magbasa ng Bibliya?",
            "death" to "Ano ang nangyayari kapag namatay tayo?"
        ),
        "sw" to mapOf(
            "saved" to "Ninawezaje kuokolewa?",
            "baptism" to "Biblia inasema nini kuhusu ubatizo?",
            "trinity" to "Utatu ni nini?",
            "prayer" to "Ninapaswa kuombaje?",
            "start_reading" to "Nianzie wapi kusoma Biblia?",
            "death" to "Nini hutokea tunapokufa?"
        ),
        "ko" to mapOf(
            "saved" to "어떻게 구원받을 수 있나요?",
            "baptism" to "성경은 세례에 대해 뭐라고 말하나요?",
            "trinity" to "삼위일체란 무엇인가요?",
            "prayer" to "어떻게 기도해야 하나요?",
            "start_reading" to "성경은 어디서부터 읽으면 좋을까요?",
            "death" to "우리가 죽으면 어떻게 되나요?"
        ),
        "zh-Hans" to mapOf(
            "saved" to "我怎样才能得救？",
            "baptism" to "圣经对洗礼是怎么说的？",
            "trinity" to "什么是三位一体？",
            "prayer" to "我应该怎样祷告？",
            "start_reading" to "我该从哪里开始读圣经？",
            "death" to "人死后会怎样？"
        )
    )

    /** The starter [id]'s question in [language] (one of SystemPrompt.languageNames), or null. */
    fun question(id: String, language: String): String? = byLanguage[language]?.get(id)
}
