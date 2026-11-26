package com.application.bibleapp.server.models

import kotlinx.serialization.Serializable

/** `POST /api/v1/search/semantic` (#287): what to look for, 1–200 characters, any language. */
@Serializable
data class SemanticSearchRequest(val q: String)

/** One verse found, best first; [bookId] is 1–66 like [VerseLocation]. [score] only orders them. */
@Serializable
data class SemanticSearchResult(val bookId: Int, val chapter: Int, val verse: Int, val score: Double)

/**
 * Up to 20 verses, best first; empty when nothing is stored yet. [keyPassages] (#320): when the
 * query matches a topic guide, its passages as USFM references ("MAT 11:28-30", "PSA 73"), to show
 * first; absent otherwise, so apps from before it see the same answer as ever. [crisis] (#324):
 * true when that guide is the crisis guide, so the app shows its help card first; absent otherwise.
 * [suggestAsk] (#401): true when the query reads like a request for help (a life situation) rather
 * than a look-up, so the app offers to ask the Bible Helper instead; absent otherwise.
 */
@Serializable
data class SemanticSearchResponse(
    val results: List<SemanticSearchResult>,
    val keyPassages: List<String>? = null,
    val crisis: Boolean? = null,
    val suggestAsk: Boolean? = null
)

/**
 * `POST /api/v1/search/reports` (#402): a search whose results didn't help, sent only when the
 * person taps Send. [q] is what they searched (1–200 characters), [language] the app's language
 * tag, [bibleVersion] the translation the results were shown in (its ID, never its text),
 * [appVersion] the app's version name. [results] and [keyPassages] are the references the app
 * showed under "Related by meaning" and "Key passages", as USFM ("JHN 3:16", "PSA 73"), at most
 * 20 each. Stored without the user or the IP.
 */
@Serializable
data class SearchReportRequest(
    val q: String,
    val language: String,
    val bibleVersion: String,
    val appVersion: String? = null,
    val results: List<String> = emptyList(),
    val keyPassages: List<String> = emptyList()
)
