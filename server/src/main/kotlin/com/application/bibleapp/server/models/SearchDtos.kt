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
 * first; absent otherwise, so apps from before it see the same answer as ever.
 */
@Serializable
data class SemanticSearchResponse(val results: List<SemanticSearchResult>, val keyPassages: List<String>? = null)
