-- Postgres doesn't auto-index a foreign key column, and every highlights/notes query in
-- SyncRoutes.kt filters by user_id (a user's own data only) — worth having as real data volume
-- grows, not just a schema-change smoke test (Phase 5).
CREATE INDEX highlights_user_id_idx ON highlights (user_id);
CREATE INDEX notes_user_id_idx ON notes (user_id);
