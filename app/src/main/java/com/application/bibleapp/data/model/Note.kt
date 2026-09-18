package com.application.bibleapp.data.model

import com.application.bibleapp.data.remote.VerseLocationDto

/**
 * A note = a set of verses (in one version) + free text — mirrors server/docs/api_contract.md.
 * Unlike [Highlight], both [verses] and [text] can be edited in place after creation.
 */
data class Note(
    val localId: Long,
    val remoteId: String?,
    val versionId: String,
    val verses: List<VerseLocationDto>,
    val text: String,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING
)
