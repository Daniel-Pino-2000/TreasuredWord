-- The Bible Helper's daily limit (#124): how many answers each user got on each UTC day. A
-- question counts only when an answer came back, and 15 a day is the limit. Deleting an account
-- deletes its counts (ON DELETE CASCADE), like every other table. Old days are never read again;
-- at a few bytes a row they can stay until a cleanup is worth writing.
CREATE TABLE ai_daily_usage (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    day DATE NOT NULL,
    count INTEGER NOT NULL,
    PRIMARY KEY (user_id, day)
);
