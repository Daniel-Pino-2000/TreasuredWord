-- Searches people chose to send because the results didn't help (#402). We don't log searches
-- (#123), so the test sets in docs/semantic_search_eval*.md are our guesses at what people type;
-- these reports are what they really type. Sent only when the person taps Send after seeing exactly
-- what goes. No user_id and no IP, like ai_reports (V7): we need the search, not who sent it. Kept
-- 90 days, like ai_reports. References are USFM ("JHN 3:16", "PSA 73") joined by ", ", easy to
-- read in Neon's SQL Editor.
CREATE TABLE search_reports (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    query TEXT NOT NULL,
    language TEXT NOT NULL,
    bible_version TEXT NOT NULL,
    app_version TEXT NULL,
    results TEXT NOT NULL,
    key_passages TEXT NOT NULL
);
-- For the 90-day cleanup and for reading the newest first.
CREATE INDEX search_reports_created_at_idx ON search_reports (created_at);
