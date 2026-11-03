-- Reported Bible Helper answers (#125). Google Play's AI-Generated Content policy requires a way
-- to report AI answers, and reports show where the instructions need fixing. There is no user_id
-- on purpose: we need the bad answer, not who reported it. Reports are kept 90 days; each new one
-- deletes those older than that. TIMESTAMP (UTC, no time zone) like every other time column here.
CREATE TABLE ai_reports (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    reason TEXT NOT NULL,
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    comment TEXT NULL,
    language TEXT NOT NULL,
    prompt_version TEXT NULL,
    CONSTRAINT ai_reports_reason CHECK (reason IN ('WRONG_TEACHING', 'WRONG_VERSE', 'HARMFUL', 'OTHER'))
);
-- For the 90-day cleanup and for reading the newest first.
CREATE INDEX ai_reports_created_at_idx ON ai_reports (created_at);
