# Search by meaning — evaluation queries

**Issue:** #287, phase 1 · Not published by GitHub Pages (`_config.yml`).

Before building search by meaning, this file is the test it's judged by. Each query lists the
verses a good result must include. The runner (`./gradlew :server:semanticSearchEval`) turns
every verse of one English translation into numbers with a Cloudflare Workers AI embedding model,
does the same for each query, and checks whether at least one expected verse is in the 10 closest.
Its report gives the hit rate overall and per group, and the neurons used.

It answers phase 1's questions with numbers instead of guesses: is `@cf/baai/bge-m3` good enough
(in English and in the app's nine other languages), does `@cf/qwen/qwen3-embedding-0.6b` do
better, and do modern queries find more with a modern translation than with the KJV.

## Running it

In PowerShell, from the repo root, **soon after 00:00 UTC** (the neurons reset then, and the Bible
Helper's users share them). `AI_BASE_URL` is the same one the Bible Helper uses; the token is a
secret, so it's typed in, never saved:

```powershell
$env:AI_BASE_URL = "https://api.cloudflare.com/client/v4/accounts/<account id>/ai/v1"
$env:AI_API_TOKEN = Read-Host -MaskInput "Workers AI token"
./gradlew :server:semanticSearchEval --configure-on-demand
```

- **First run:** embeds all 31,102 KJV verses (from the app's own `bible_default.db`, no
  download). That's about 1.1 million tokens, **about 1,200 of the free 10,000 neurons a day**,
  and roughly 10–20 minutes in batches of 32. The vectors are saved as they arrive in
  `server/build/semantic-search-eval/cache/`.
- **Later runs** read the vectors from that cache and only embed the queries (well under 1
  neuron). `./gradlew clean` deletes the cache, and the next run pays the ~1,200 neurons again.
- **If the neurons run out** (Cloudflare error 3036), the runner stops, keeps what it embedded,
  and says which verse it stopped at. Run the same command after 00:00 UTC and it carries on from
  there.
- **Report:** `server/build/semantic-search-eval/<date>-<model>-<translation>.md`, with the hit
  rate and a table of every query, then each query's top 10.
- **Options:** `-PembeddingModel=@cf/qwen/qwen3-embedding-0.6b` for the comparison run (it has
  its own cache, so its first run also costs ~1,200 neurons); `-Pqueries=TOPIC-01,LANG-KO-01` runs
  only those (a `-partial` report); `-Pbible=<file.tsv>` embeds another translation instead of the
  KJV (below).
- **Neurons** in the report are estimated from the token count (1,075 neurons per million tokens
  for both models). Cloudflare's dashboard (AI → Workers AI) shows the real figure.

It never runs in `:server:test` or CI. It logs only HTTP status, timing and neurons, never the
token or a query.

### Another translation

A modern public-domain translation (BSB or WEB) may suit modern queries better than the KJV. To
compare, give the runner a tab-separated file with one verse per line, the book as a USFM code:

```
GEN	1	1	In the beginning God created the heavens and the earth.
```

and run with `-Pbible=path/to/bsb.tsv`. The file's name (`bsb`) names the cache and the report.
References are the same in every translation, so the expected verses below don't change.

## How a query is written

The runner reads every `###` heading after "## Queries" as one query:

```
### TOPIC-01
- **Query:** what a person types into Search
- **Language:** the app language (optional; `en` when missing)
- **Expect:** PHP 4:6-7, MAT 6:34
```

**Expect** lists the verses, as USFM book codes (the same as `docs/ai_helper_eval.md` and the
app's verse cards); `PHP 4:6-7` means both verses. A query **hits** when at least one of them is in
the top 10. Every expected verse was checked against the KJV text to say what the query asks; a
guessed verse would make a good model look bad.

The groups: **TOPIC**, the kind of word people type in Search today; **MOD**, a modern sentence
whose KJV verses use other words; **KJV**, an exact KJV phrase (word search finds these already,
so search by meaning must not do worse); **LANG**, queries in the app's other languages, answered
by verses embedded in English.

## Queries

## Topics

### TOPIC-01
- **Query:** worry
- **Expect:** PHP 4:6-7, MAT 6:25, MAT 6:34, 1PE 5:7, PSA 55:22

### TOPIC-02
- **Query:** forgiving someone who hurt me
- **Expect:** MAT 6:14-15, MAT 18:21-22, MRK 11:25, LUK 17:3-4, EPH 4:32, COL 3:13

### TOPIC-03
- **Query:** being kind to people who hate me
- **Expect:** MAT 5:44, LUK 6:27-28, LUK 6:35, ROM 12:20, PRO 25:21

### TOPIC-04
- **Query:** saved by grace, not by good works
- **Expect:** EPH 2:8-9, TIT 3:5, ROM 3:24, ROM 4:5, GAL 2:16

### TOPIC-05
- **Query:** gossip
- **Expect:** PRO 11:13, PRO 16:28, PRO 18:8, PRO 20:19, PRO 26:20, LEV 19:16, 1TI 5:13

### TOPIC-06
- **Query:** resisting temptation
- **Expect:** 1CO 10:13, JAS 1:12, JAS 1:13-14, MAT 26:41, HEB 4:15

### TOPIC-07
- **Query:** what heaven will be like
- **Expect:** REV 21:1-4, REV 22:3-5, JHN 14:2-3

### TOPIC-08
- **Query:** giving generously to the poor
- **Expect:** 2CO 9:7, PRO 19:17, PRO 22:9, ACT 20:35, LUK 6:38, DEU 15:10-11

## Modern phrasing

### MOD-01
- **Query:** I can't sleep because my mind won't stop racing
- **Expect:** PSA 4:8, PSA 3:5, PSA 127:2, PRO 3:24, PHP 4:6-7

### MOD-02
- **Query:** I'm completely burned out and exhausted
- **Expect:** MAT 11:28-30, ISA 40:29-31, GAL 6:9, 2CO 4:16

### MOD-03
- **Query:** God feels far away and silent
- **Expect:** PSA 10:1, PSA 13:1, PSA 22:1, PSA 145:18, JAS 4:8, PSA 34:18

### MOD-04
- **Query:** does God really love me?
- **Expect:** JHN 3:16, ROM 5:8, ROM 8:38-39, 1JN 4:9-10, 1JN 4:16, JER 31:3

### MOD-05
- **Query:** my best friend betrayed me
- **Expect:** PSA 41:9, PSA 55:12-14

### MOD-06
- **Query:** I keep comparing myself to people on social media
- **Expect:** 2CO 10:12, GAL 6:4, GAL 5:26, PHP 2:3

### MOD-07
- **Query:** I keep messing up and feel like a failure
- **Expect:** 1JN 1:9, PRO 24:16, ROM 8:1, LAM 3:22-23, PSA 37:23-24, PHP 3:13-14

### MOD-08
- **Query:** how to deal with a difficult boss at work
- **Expect:** COL 3:22-23, EPH 6:5-7, 1PE 2:18, TIT 2:9-10

### MOD-09
- **Query:** I just got a scary diagnosis from the doctor
- **Expect:** JAS 5:14-15, 2CO 12:9, PSA 41:3, PSA 103:2-3, JER 17:14, ISA 41:10, PSA 56:3

### MOD-10
- **Query:** I feel so lonely, like nobody cares about me
- **Expect:** PSA 142:4, PSA 25:16, PSA 102:7, PSA 68:6, 2TI 4:16, HEB 13:5, ISA 49:15

### MOD-11
- **Query:** feeling depressed and hopeless
- **Expect:** PSA 42:5, PSA 42:11, PSA 34:17-18, PSA 40:1-2, PSA 143:7-8, ISA 61:3

### MOD-12
- **Query:** how do I know what God wants me to do with my life
- **Expect:** PRO 3:5-6, PRO 16:9, PRO 19:21, PSA 32:8, ROM 12:2, JER 29:11

## Exact KJV phrases

### KJV-01
- **Query:** the Lord is my shepherd
- **Expect:** PSA 23:1

### KJV-02
- **Query:** be still, and know that I am God
- **Expect:** PSA 46:10

### KJV-03
- **Query:** in my Father's house are many mansions
- **Expect:** JHN 14:2

### KJV-04
- **Query:** a soft answer turneth away wrath
- **Expect:** PRO 15:1

### KJV-05
- **Query:** lean not unto thine own understanding
- **Expect:** PRO 3:5

## Other languages

### LANG-DE-01
- **Query:** Ich habe Angst vor der Zukunft
- **Language:** de
- **Expect:** JER 29:11, MAT 6:34, ISA 41:10, PHP 4:6-7, 2TI 1:7

### LANG-DE-02
- **Query:** Was sagt die Bibel über Hochmut?
- **Language:** de
- **Expect:** PRO 16:18, PRO 11:2, PRO 29:23, JAS 4:6, 1PE 5:5

### LANG-ES-01
- **Query:** ¿Cómo perdono a alguien que me hizo daño?
- **Language:** es
- **Expect:** MAT 6:14-15, MAT 18:21-22, MRK 11:25, LUK 17:3-4, EPH 4:32, COL 3:13

### LANG-ES-02
- **Query:** murió alguien a quien amaba
- **Language:** es
- **Expect:** 1TH 4:13-14, JHN 11:25, REV 21:4, MAT 5:4, PSA 34:18, PSA 147:3, 2CO 1:3-4

### LANG-FR-01
- **Query:** je me sens seul
- **Language:** fr
- **Expect:** PSA 142:4, PSA 25:16, PSA 102:7, PSA 68:6, 2TI 4:16, HEB 13:5, DEU 31:6, MAT 28:20

### LANG-FR-02
- **Query:** l'argent et la cupidité
- **Language:** fr
- **Expect:** 1TI 6:10, 1TI 6:17, HEB 13:5, MAT 6:24, LUK 12:15

### LANG-PT-01
- **Query:** estou com muita ansiedade
- **Language:** pt-BR
- **Expect:** PHP 4:6-7, MAT 6:25, MAT 6:34, 1PE 5:7, PSA 55:22

### LANG-PT-02
- **Query:** como devo orar?
- **Language:** pt-BR
- **Expect:** MAT 6:6, MAT 6:9-13, LUK 11:1-4, 1TH 5:17, PHP 4:6

### LANG-ID-01
- **Query:** bagaimana mengasihi orang yang membenci saya
- **Language:** id
- **Expect:** MAT 5:44, LUK 6:27-28, LUK 6:35, ROM 12:20, PRO 25:21

### LANG-ID-02
- **Query:** diselamatkan oleh kasih karunia, bukan karena perbuatan baik
- **Language:** id
- **Expect:** EPH 2:8-9, TIT 3:5, ROM 3:24, ROM 4:5, GAL 2:16

### LANG-FIL-01
- **Query:** natatakot ako
- **Language:** fil
- **Expect:** ISA 41:10, 2TI 1:7, PSA 56:3, PSA 27:1, PSA 34:4, JOS 1:9

### LANG-FIL-02
- **Query:** paano maging matiyaga habang naghihintay sa Diyos
- **Language:** fil
- **Expect:** PSA 27:14, PSA 37:7, PSA 40:1, ISA 40:31, LAM 3:25-26, JAS 5:7-8, ROM 8:25

### LANG-SW-01
- **Query:** faraja wakati wa huzuni
- **Language:** sw
- **Expect:** MAT 5:4, PSA 34:18, PSA 147:3, 2CO 1:3-4, ISA 66:13, JHN 14:1

### LANG-SW-02
- **Query:** uvivu na kufanya kazi kwa bidii
- **Language:** sw
- **Expect:** PRO 6:6, PRO 10:4, PRO 13:4, 2TH 3:10, COL 3:23

### LANG-KO-01
- **Query:** 하나님은 세상을 어떻게 창조하셨나요?
- **Language:** ko
- **Expect:** GEN 1:1, PSA 33:6, JHN 1:3, COL 1:16, HEB 11:3

### LANG-KO-02
- **Query:** 남편과 아내의 관계
- **Language:** ko
- **Expect:** GEN 2:24, EPH 5:22, EPH 5:25, EPH 5:31, MAT 19:6, MRK 10:9

### LANG-ZH-01
- **Query:** 如何控制怒气
- **Language:** zh-Hans
- **Expect:** EPH 4:26, EPH 4:31, JAS 1:19-20, PRO 14:29, PRO 15:1, PRO 16:32, PRO 29:11, PSA 37:8

### LANG-ZH-02
- **Query:** 孝敬父母
- **Language:** zh-Hans
- **Expect:** EXO 20:12, EPH 6:1-3, COL 3:20
