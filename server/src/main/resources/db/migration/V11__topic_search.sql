-- A topical index for search by meaning (#394): Nave's Topical Bible's entries ("Envy", "Envy,
-- instances of: Cain, of Abel") with their verses. A query close to an entry by meaning gets that
-- entry's verses ranked as a third list beside the vectors and the keywords (V9). Measured on the
-- held-out set (docs/semantic_search_eval_heldout.md): 74 → 84 of 132 queries with an expected
-- verse in the top 10, no loss on the main set.
--
-- Filled by `./gradlew :server:loadTopicSearch`, never by the server. While these tables are empty
-- the search ranks exactly as before, so emptying them is the off switch. Source data:
-- NavesTopicalDictionary.csv from github.com/BradyStephenson/bible-data (CC BY 4.0, from the
-- public-domain 1896 text); docs/bible-licensing.md has the credit.

-- One row per entry and model. halfvec as in verse_embeddings: ~22,000 entries are ~45 MB.
CREATE TABLE topic_entries (
    source TEXT NOT NULL,
    model TEXT NOT NULL,
    id INTEGER NOT NULL,
    label TEXT NOT NULL,
    embedding HALFVEC(1024) NOT NULL,
    PRIMARY KEY (source, model, id)
);

-- The verses each entry names, whole chapters expanded to their verses: ~209,000 rows.
CREATE TABLE topic_entry_verses (
    source TEXT NOT NULL,
    id INTEGER NOT NULL,
    book SMALLINT NOT NULL,
    chapter SMALLINT NOT NULL,
    verse SMALLINT NOT NULL,
    PRIMARY KEY (source, id, book, chapter, verse),
    CONSTRAINT topic_entry_verses_book CHECK (book BETWEEN 1 AND 66)
);
