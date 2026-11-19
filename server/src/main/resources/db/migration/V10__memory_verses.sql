-- The Memorize list (#362): passages someone wants to learn by heart, apart from highlights,
-- with the practice progress the games (#363) move along. A passage is one book and chapter,
-- verse_start..verse_end (at most 6 verses, checked in MemoryVerseRoutes.kt). progress_at is
-- when the progress last changed on a device: an older device's write never replaces newer
-- progress (docs/api_contract.md decision 21). Soft deletes, like highlights and notes.
CREATE TABLE memory_verses (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    version_id TEXT NOT NULL,
    book_id INTEGER NOT NULL,
    chapter INTEGER NOT NULL,
    verse_start INTEGER NOT NULL,
    verse_end INTEGER NOT NULL,
    status TEXT NOT NULL,
    level INTEGER NOT NULL,
    next_review_at TIMESTAMP NULL,
    last_practiced_at TIMESTAMP NULL,
    correct_streak INTEGER NOT NULL,
    memorized_at TIMESTAMP NULL,
    progress_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP NULL
);
CREATE INDEX memory_verses_user_id_idx ON memory_verses (user_id);
