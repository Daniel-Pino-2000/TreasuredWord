package com.application.bibleapp.data.model

/**
 * Local-first sync state for a highlight/note row (see BibleDatabaseManager). PENDING covers
 * both "never pushed" and "changed locally since the last push" — the sync worker (Phase E)
 * pushes every PENDING row, including soft-deleted ones, then marks it SYNCED.
 */
enum class SyncStatus {
    PENDING,
    SYNCED;

    companion object {
        fun fromStored(value: String): SyncStatus = runCatching { valueOf(value) }.getOrDefault(PENDING)
    }
}
