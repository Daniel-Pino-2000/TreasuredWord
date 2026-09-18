package com.application.bibleapp.data.model

import com.application.bibleapp.data.remote.VerseLocationDto

/**
 * A highlight = a set of verses (in one version) + a color — mirrors server/docs/api_contract.md.
 * [localId] is this device's row id, always set; [remoteId] is the server's UUID, null until the
 * first successful sync. The verse list is immutable after creation (contract decision 14); only
 * [color] can change post-creation.
 */
data class Highlight(
    val localId: Long,
    val remoteId: String?,
    val versionId: String,
    val verses: List<VerseLocationDto>,
    val color: Int,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING
)
