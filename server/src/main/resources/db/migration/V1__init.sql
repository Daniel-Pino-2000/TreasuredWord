-- Baseline schema, matching what SchemaUtils.create(Users, RefreshTokens, Highlights, Notes,
-- ReadingProgress) previously generated at startup — see db/tables/*.kt for the Exposed table
-- definitions this mirrors and their design-decision comments. From here on, schema changes go
-- through a new V{n}__description.sql file instead of being inferred at startup (Phase 5).

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX users_email_unique ON users (email);

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL
);
CREATE UNIQUE INDEX refresh_tokens_token_hash_unique ON refresh_tokens (token_hash);

CREATE TABLE highlights (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    version_id TEXT NOT NULL,
    verses_json TEXT NOT NULL,
    color INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP NULL
);

CREATE TABLE notes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    version_id TEXT NOT NULL,
    verses_json TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP NULL
);

CREATE TABLE reading_progress (
    user_id UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    version_id TEXT NOT NULL,
    book_id INTEGER NOT NULL,
    chapter INTEGER NOT NULL,
    verse INTEGER NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
