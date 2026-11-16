-- Search by meaning (#287, phase 2): what the search endpoint (phase 3) ranks verses with, as the
-- evaluation chose: bge-m3 vectors of the BSB plus Postgres full-text search on the BSB text,
-- fused by reciprocal rank. Filled by `./gradlew :server:loadVerseSearch`, never by the server
-- itself. Only references leave the server; the app shows each verse in the reader's own
-- translation. Neon's free plan includes pgvector; halfvec needs pgvector 0.7 or later.
CREATE EXTENSION IF NOT EXISTS vector;

-- The text the keywords half searches: public-domain BSB (bereanbible.com), one row per verse.
-- words is computed by Postgres, so it can't drift from text. ~15 MB with its index (measured).
CREATE TABLE search_verses (
    translation TEXT NOT NULL,
    book SMALLINT NOT NULL,
    chapter SMALLINT NOT NULL,
    verse SMALLINT NOT NULL,
    text TEXT NOT NULL,
    words TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', text)) STORED,
    PRIMARY KEY (translation, book, chapter, verse),
    CONSTRAINT search_verses_book CHECK (book BETWEEN 1 AND 66)
);
CREATE INDEX search_verses_words_idx ON search_verses USING GIN (words);

-- One vector per verse and model. halfvec stores 2 bytes a number: 1,024 numbers × 31,086 verses
-- measured 87 MB with the key, of Neon's 1 GB. model is part of the key so a better model can be
-- loaded beside this one and switched to without a gap. No vector index yet: phase 3 times the
-- search on Neon first and adds HNSW only if it's slow (#287).
CREATE TABLE verse_embeddings (
    model TEXT NOT NULL,
    translation TEXT NOT NULL,
    book SMALLINT NOT NULL,
    chapter SMALLINT NOT NULL,
    verse SMALLINT NOT NULL,
    embedding HALFVEC(1024) NOT NULL,
    PRIMARY KEY (model, translation, book, chapter, verse),
    CONSTRAINT verse_embeddings_book CHECK (book BETWEEN 1 AND 66)
);
