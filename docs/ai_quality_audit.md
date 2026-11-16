# Bible Helper and search by meaning — quality audit

**Date:** 2026-11-16 · **Issues:** #287, #297 · Not published by GitHub Pages (`_config.yml`).

Why the Bible Helper's answers stopped improving after v5, why giving it verses from search (v15)
made them worse, what search by meaning does well and badly, and what to change. Every finding
below comes from the evaluation reports since v1 (22 runs of the Gemma 4 26B model) or from a
measurement made for this audit.

## Summary

1. **The measurement can't see a real change.** The same v5 instructions scored 89%, 74% and 77%
   Good depending on who graded; v14 scored 69% and then 59% on identical instructions. Every
   version since v5 lies inside that noise, so "nothing improves" partly means "nothing could be
   measured improving".
2. **Most misses are missing content, not missing rules.** About 10 of v5's 19 recurring Needs
   work lack one specific thing a pastor would bring up: the thief on the cross, Romans 6:1-2,
   the fourth rapture view, the KJV-only view, Peter beside Judas, Psalm 119:105.
3. **Rules can't add that content safely.** v6–v14 added it as instructions, which every
   question reads. Each fixed a few questions and broke others, because a model with 4B active
   parameters can't keep dozens of rules in mind at once.
4. **Verse errors are misattributions.** In ~5,000 citations the model cited a verse that doesn't
   exist twice. The problem is real verses cited for points they don't make, and `doctrine.md`
   gives it no Scripture to draw on: every reference comes from memory.
5. **Search finds verses that sound like the question, not verses that answer it.** That is why
   it misses life situations, and why the verses it handed the Bible Helper in v15 made answers
   worse (58% Good with a Wrong answer).

The fix that addresses all five is **hand-checked topic guides**, sent only with the questions
they fit. Section 2 is the pilot that tested it.

## 1. Findings

### 1.1 The evaluation's noise is larger than the changes it measures

| Version | Good | What changed |
|---|---|---|
| v5 (11-05) | 89% | graded leniently |
| v5 control (11-09) | 74%, 77% | the same instructions, graded strictly |
| v6 | 65%, 67% | answer shape |
| v7 | 72%, 70% | |
| v8 | 81% | one run |
| v10 | 60% | framing |
| v11 | 75%, 70% | |
| v12 | 77%, 75% | v5 with v10's doctrine; one Wrong |
| v13 | 65%, 72% | crisis verse |
| v14 | 69%, 59% | lean instructions |
| v15 | 58% | verses from search; one Wrong |

- **Grader drift:** the same instructions lost 12–15 points when the grading became strict, and
  later reports note that a point or two either way can come from the grader.
- **Run-to-run noise:** at temperature 0.2, two runs of the same instructions differ by up to 10
  points (v14).
- **All-or-nothing grades:** an answer is Good only if it makes every point of its Must line,
  often 3–5 points including a named verse. One missed point and the whole answer drops, so the
  Good share swings with a few points.

So no change smaller than about 10 points can be told from noise with one or two runs, and every
version since v5 except v10 and v15 is inside that band.

### 1.2 What the misses are

v5's control runs (the production instructions) had 19 Needs work rows:

| Kind | Rows | Examples |
|---|---|---|
| Missing content | ~10 | no view without a separate rapture (DIS-02), never describes the KJV-only view (DIS-05), no John 11:35 (LIFE-01), no safety line (LIFE-04), no Psalm 119:105 (LIFE-05), no "not your fault" (CRISIS-02), no Peter (CRISIS-05), no Lord's Prayer (LANG-04) |
| Borderline citations | 4–5 | John 10:27-29 for "His blood remains effective" (CORE-07), 1 Corinthians 1:10 for "conventions have no authority" (BAP-06) |
| Tone or safety | 2 | an unasked self-harm line (LIFE-06), touching the forgiveness question (CRISIS-01) |
| Facts | 2 | Acts called a Gospel (CRISIS-05), Hezekiah called a prophet (TRICK-03) |

The same content misses recur across v5–v14. When an instruction supplied the content, the
model used it: v14's "it isn't your fault" fixed CRISIS-02 in both runs, and its KJV-only line
fixed DIS-05. But v14 also lost the resurrection in CORE-04 and CORE-05, "Christ judges everyone"
in CORE-09 and love for neighbors in TRAD-05, none of which it had touched.

### 1.3 Citations

Across the 22 Gemma runs (~5,050 `[[…]]` markers, checked against the BSB verse list):

- **2** cite a verse that doesn't exist (`HEB 2:35`, `MAT 4:35-38`); a third writes Jude 14 as
  `JUD 14`.
- **~130** are malformed or use a wrong book code (`PHIL 4:6`, `1 JHN 1:9`, `[[JHN]]`). The app already reads or hides
  these (#190, #222, #295), so they don't reach the screen as brackets.
- **The rest exist.** The graded errors are all misattributions: a real verse cited for a point
  it doesn't make. Every reference comes from the model's memory, since neither
  `system_prompt.md` nor `doctrine.md` (no Scripture references at all) gives it any.

### 1.4 Search by meaning

Phase 1 measured 26 of 43 queries with an expected verse in the top 10 by vectors alone (27 with
the full-text half the live endpoint adds):

| Kind of query | Hits |
|---|---|
| A topic word ("gossip", "worry") | 6 of 8 |
| A modern sentence ("I'm completely burned out and exhausted") | 5 of 12 |
| An exact KJV phrase | 5 of 5 |
| Other languages | 10 of 18 |

The misses share a cause: vectors find verses that *sound like* the query. "Feeling depressed
and hopeless" returns laments (Romans 9:2, Psalm 38:10, Psalm 31:12) rather than the verses that
answer it (Psalm 42:11, Lamentations 3:22-23). For a word search that's useful; for a person
asking for help it's the wrong half of the Bible.

### 1.5 Why the verses from search made answers worse (v15)

The Bible Helper's questions are whole sentences, the kind search does worst on. The 8 verses
contained only 7 of the 65 passages the questions call for, and the model cited the off-topic
ones anyway: 8 of the run's 9 weak or wrong citations came from the list. Retrieval by
similarity can't work here, because similar isn't the same as relevant.

## 2. The pilot: topic guides

### What a guide is

`server/src/main/resources/ai/topics.md` (pilot branch `experiment/topic-guides`) holds 48 guides,
for the questions people ask most: salvation, baptism, the rapture, Bible translations, alcohol,
grief, worry, repeated sin, marriage, abuse, crisis and so on. Each has:

- **Asks:** a few example questions, in English.
- **Points:** the 2–4 things a good answer says, taken from `doctrine.md` or a decision the
  developer already made (alcohol, abuse, grief: v14's items 18, 19, 21).
- **Passages:** 3–7 references, each with a note on what it supports. All 225 were checked
  against their BSB text for this audit.

A question is matched to guides by meaning: bge-m3 vectors of the question and of every "Asks"
phrase (multilingual, so a Swahili question matches an English phrase). A guide is sent only when
the match scores 0.70 or more, after v5's instructions, unchanged. A question with no match gets
v5's instructions exactly, so a guide can never make an unrelated answer worse, which is what
every global rule since v6 did.

### Routing

`./gradlew :server:topicRoutingEval` (3 neurons). Measured on questions whose wording the guides
don't share:

| At 0.70 | Right guide | Wrong guide | No guide when one fit | No guide, correctly |
|---|---|---|---|---|
| 20 held-out questions (`docs/ai_helper_eval_heldout.md`) | 17 | 0 | 1 (grief, 0.62) | 2 of 2 |
| 18 search queries in nine other languages | 18 | 0 | 0 | – |

The main set's 57 questions and the English search queries route right too, but many of the
guides' phrases were written from them, so those numbers don't count as evidence. They did catch
one real risk: "Will God forgive me if I end it?" matched the guide on repeated sin and
forgiveness. A crisis guide that restates the crisis rules now matches such questions as well.

### Answers

Two runs of v16 (v5's instructions plus the matching guides), at temperature 0.2 like
production, on the 35 main questions that matched a guide and the 17 held-out questions that
did. The other 22 main questions got no guide, so their instructions were v5's exactly. Graded
as strictly as the v5 control runs.

| | v5 | v16 (guides) |
|---|---|---|
| **Main set, same 35 questions** (v5: control runs of 11-09) | 20 and 24 Good (57%, 69%) | **31 and 31 Good (89%)** |
| **Held-out, 17 questions**, graded blind (A/B shuffled, key opened after grading) | 3 and 7 Good (18%, 41%) | **16 and 16 Good (94%)** |
| Wrong answers | 2 (held-out run 1) | **0** |
| Passages named on the Must lines, cited | 60–73% | **100%** in all four runs |
| Citations that don't make their point | 4–5 a run, plus the 2 Wrong | **0** |
| Citations taken from the guide | – | 92–94%; every other one checked and correct |
| Neurons per answer | ~30–33 | ~35–36 |

- **What changed most:** the points v5 kept missing are made in every run: all four rapture
  views, the KJV-only view, Psalm 119:105, the Lord's Prayer, John 11:35, "it isn't your
  fault", Peter beside Judas, the safety line in marriage questions, Romans 6:1-2.
- **Citations:** in 4 runs and ~590 citations, no borderline or misattributed one. Each passage
  arrives with a note on what it supports, and the model cites it for that.
- **What it didn't fix:**
  - **Alcohol** (DIS-06, HO-05, all four runs): the model reports that "many Baptists" abstain
    instead of recommending it, though the guide says to recommend it.
  - **CRISIS-01** (both runs): safe and short, but says "I can't answer whether God forgives…",
    which names the question. The guide's wording ("don't discuss whether God forgives") invites
    it.
  - **TRICK-08** (both): `[[CRISIS]]` on the antidepressant question, as v5 also did on 11-05;
    no guide is involved.
  - Swahili still has misspelled words, and BAP-06 run 2 implies deacons are men (as v5 did).
- **Caveats, honestly:** the guides were written with the main set in view, so its gain is
  partly built in. The held-out Must lines were also written in the same sitting, so they expect
  the same points the guides carry; blind grading removes bias in judging, not in what's asked.
  The grading-free rows (Must-named passages, citations, Wrong answers) don't depend on that, and
  they show the same change. The real test is questions from real users, which only reports can
  give us (we don't log questions).

### Search

Showing the matching guide's passages first, then search's own results, lifts the 43 search
queries from 27 to 41 with an expected verse in the top 10 (at 0.70). It costs nothing more:
search already embeds the query, and matching it to ~300 stored phrase vectors is arithmetic. The
guides' passages and the queries' expected verses are both the standard passages for each topic,
so the 41 mostly measures the routing, which held up in nine languages.

## 3. Recommendations

In order. Each one is free and stays inside the current model and free plans.

1. **Topic guides in the Bible Helper.** Match each question on the server and send the matching
   guides, as the pilot did; with no match, or if the embedding fails, answer with v5 exactly.
   - **Before production:** fix the two wordings the pilot found (alcohol: give the
     recommendation in the answer's own voice; crisis: leave the forgiveness question out
     entirely), the developer reviews every guide (they carry positions), and two full runs of
     all 57 questions and the held-out set confirm the pilot.
   - Precompute the guides' phrase vectors at build time (a resource file, checked by a test
     against `topics.md`), so the server never embeds them and starts without Workers AI.
   - It replaces #297's goal: the doctrine stays as approved, and new positions go in guides
     that are only read when they apply. #297 can close once guides ship.
   - Production stays on v5 until the developer switches (#290's decision).
2. **"Key passages" in Search.** When a query matches a guide, show its passages first, above
   "Related by meaning": 27 → 41 of 43 in the pilot, for no extra neurons, since the query is
   already embedded. A heading ("Key passages") and the verse cards need no per-topic
   translations.
3. **A better evaluation.** What made the pilot's result clear:
   - **Grade blind:** shuffle the old and new answer as A and B, save the grades, then open the
     key (the pilot's `blind*` files, beside the reports in `server/build/ai-eval/`, gitignored).
   - **Grading-free numbers in every report:** Must-named passages cited, citations from the
     guide, and every other citation listed for checking.
   - **The held-out set** (`docs/ai_helper_eval_heldout.md`), grown from real reports (#125)
     rather than written next to the guides.
   - Compare the **same questions**, two runs each, and report the per-question changes.
4. **Not recommended:**
   - More global rules in `system_prompt.md` (v6–v14 show each one costs other answers).
   - Verses from search in the prompt (v15).
   - Another model (researched 2026-11-09; the developer chose Gemma), or thinking mode
     (~56 neurons and 20–40 s an answer, 4 of 10 ran out of tokens).

## 4. Cost on the free plans

- **Bible Helper:** a guided answer costs ~4–6 more neurons (~36 instead of ~30–33); about 60%
  of the main set's questions matched a guide. Roughly 300 answers a day instead of 320, of the
  free 10,000 neurons. Matching costs one embedding a question (~0.01 neurons), counted against
  search's daily cap like v15's design.
- **Search:** nothing more.
- **Neon:** nothing; the phrase vectors live in the server's resources (~1.5 MB).
- **This audit** spent ~7,000 neurons on 2026-11-16 UTC: v15's run (~2,000) and the pilot's six
  runs (~5,000), all against a local copy of the verse tables, never Neon.

## 5. Reproducing it

On `feature/ai-quality-audit`, with a local Postgres loaded by `loadVerseSearch`
(`DATABASE_URL`) and the Workers AI settings (`docs/ai_helper_eval.md`, "Running it"):

```powershell
./gradlew :server:topicRoutingEval --configure-on-demand           # routing, ~3 neurons
$env:AI_TOPIC_GUIDES = "true"; $env:AI_TOPIC_THRESHOLD = "0.70"; $env:AI_ONLY_GUIDED = "true"
./gradlew :server:aiEval --configure-on-demand                      # main set, guided questions
./gradlew :server:aiEval --configure-on-demand -PquestionsFile=docs/ai_helper_eval_heldout.md
```
