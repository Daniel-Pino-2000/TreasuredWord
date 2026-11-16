-- Ready answers to the Ask tab's starter questions (#284): one per starter, language and
-- instructions version. The first person to tap a starter in a language gets a fresh answer, stored
-- here; everyone after gets it instantly, without using one of their daily questions or any of the
-- AI's free neurons. A new prompt version asks again, so old answers are simply never read (at most
-- 60 rows per version). No user_id: the answer is the same for everyone.
CREATE TABLE ai_starter_answers (
    starter TEXT NOT NULL,
    language TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    answer TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (starter, language, prompt_version)
);
