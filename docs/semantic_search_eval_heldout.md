# Search by meaning — held-out queries

**Issue:** #393 · Not published by GitHub Pages (`_config.yml`).

The main set (`docs/semantic_search_eval.md`, 43 queries) was written next to the topic guides, so
the guides' passages and its expected verses are the same standard passages, and "Key passages"
(#320) scores 41 of 43 on it. That shows the routing works, not how search does on what people
actually type. We don't log searches (#123), so real queries can't be the source.

These 132 queries were written for #393 **without reading `server/src/main/resources/ai/topics.md`**:
everyday sentences, topics none of the 50 guides is about, Bible stories, and all nine other
languages. Some will still match a guide, as real searches would; the report says how many.

Every expected verse was checked against its BSB text on 2026-11-22 (the translation search ranks).
A query **hits** when at least one expected verse is in the top 10.

## Running it

The same runner as the main set, with the queries file:

```powershell
./gradlew :server:semanticSearchEval --configure-on-demand -Pbible=server/build/semantic-search-eval/bsb.tsv -PqueriesFile=docs/semantic_search_eval_heldout.md
```

- The verse vectors come from the cache (`bge-m3-bsb.vec`), so only the queries are embedded: about
  1 neuron the first time, nothing after that (query vectors are cached by wording). It needs
  `AI_BASE_URL` and `AI_API_TOKEN` only on that first run (`docs/semantic_search_eval.md`, "Running it").
- Reports go to `server/build/semantic-search-eval/<date>-bge-m3-bsb-heldout*.md`. The variants
  report ends with **Key passages first**: search alone next to what the Search screen shows, per
  group, and how many queries matched a guide.

The groups: **LIFE**, an everyday sentence about the person's life; **NEW**, a topic word or phrase
none of the guides is about; **STORY**, a Bible story or passage described in plain words (any verse
of it counts); **LANG**, the same kinds in the app's nine other languages.

## First run (2026-11-22): today's search

`bge-m3` vectors of the BSB with full-text search (RRF), the live ranking, plus Key passages
(#320) as the Search screen shows them. Cost: about 1 neuron for the 132 queries.

| Group | Queries | Matched a guide | Search alone | With Key passages (what Search shows) |
|---|---|---|---|---|
| LIFE (everyday sentences) | 40 | 20 | 9 (23%) | 16 (40%) |
| NEW (topics no guide covers) | 40 | 6 | 28 (70%) | 27 (68%) |
| STORY | 25 | 2 | 20 (80%) | 20 (80%) |
| LANG (other languages) | 27 | 7 | 8 (30%) | 11 (41%) |
| **All** | **132** | **35** | **65 (49%)** | **74 (56%)** |

- **Everyday sentences are the weak spot.** Search finds verses that sound like the sentence:
  "my dad is in the hospital and I'm scared" brings up Leviticus 13's skin diseases and Psalm 55's
  terror, not Psalm 46:1 or Isaiah 41:10. A guide fixes the ones it matches (9 → 16), but only half
  of these sentences match one.
- **Other languages miss most when no guide matches:** the keyword half only reads English, so
  they rest on the vectors alone.
- **Topics and stories do well already** (68–80%), and a guide adds nothing there: few match one.
- On the main set (written with the guides), the same search scores 27 of 43 alone and 42 of 43
  with Key passages: the guides carry it.

## Queries

## Everyday sentences

### LIFE-01
- **Query:** my dad is in the hospital and I'm scared
- **Expect:** PSA 46:1, ISA 41:10, PHP 4:6-7, PSA 23:4, JAS 5:14-15

### LIFE-02
- **Query:** I lied to my boss and I feel guilty
- **Expect:** 1JN 1:9, PRO 28:13, PSA 32:5, PSA 51:1-2, EPH 4:25

### LIFE-03
- **Query:** I can't sleep at night
- **Expect:** PSA 4:8, PSA 127:2, PRO 3:24, PSA 3:5

### LIFE-04
- **Query:** my girlfriend broke up with me
- **Expect:** PSA 34:18, PSA 147:3, ISA 43:18-19, PHP 3:13-14, PRO 3:5-6

### LIFE-05
- **Query:** I'm jealous of my friend's success
- **Expect:** PRO 14:30, JAS 3:14-16, GAL 5:26, 1CO 13:4, PHP 4:11-12

### LIFE-06
- **Query:** I keep procrastinating and being lazy
- **Expect:** PRO 6:6-11, PRO 13:4, PRO 20:4, COL 3:23, ECC 9:10, 2TH 3:10

### LIFE-07
- **Query:** my kids won't listen to me
- **Expect:** EPH 6:4, PRO 22:6, PRO 29:17, COL 3:21, DEU 6:6-7

### LIFE-08
- **Query:** we just had a baby
- **Expect:** PSA 127:3, PSA 139:13-14, DEU 6:6-7, PRO 22:6

### LIFE-09
- **Query:** my wife and I fight all the time
- **Expect:** EPH 4:26, EPH 4:31-32, COL 3:19, 1PE 3:7, JAS 1:19, PRO 15:1

### LIFE-10
- **Query:** I'm getting old and my body is failing
- **Expect:** 2CO 4:16-18, ISA 46:4, PSA 71:9, PSA 71:18, PSA 92:14

### LIFE-11
- **Query:** I'm single and I want to get married
- **Expect:** 1CO 7:7-8, 1CO 7:32-35, PSA 37:4-5, PHP 4:11-13

### LIFE-12
- **Query:** a coworker keeps taking credit for my work
- **Expect:** COL 3:23-24, PSA 37:7-8, ROM 12:19, 1PE 2:19-23

### LIFE-13
- **Query:** I can't stop looking at porn
- **Expect:** 1CO 6:18-20, MAT 5:28-29, 1CO 10:13, JOB 31:1, PSA 119:9, 1TH 4:3-5

### LIFE-14
- **Query:** I'm addicted to my phone
- **Expect:** EPH 5:15-16, 1CO 6:12, PSA 90:12, COL 3:2, PHP 4:8

### LIFE-15
- **Query:** my parents are getting divorced
- **Expect:** PSA 27:10, PSA 34:18, ISA 41:10, PSA 147:3, ROM 8:28

### LIFE-16
- **Query:** I'm thinking about getting a divorce
- **Expect:** MAT 19:6, MAL 2:16, MAT 19:8-9, 1CO 7:10-11, MRK 10:9

### LIFE-17
- **Query:** we can't have children
- **Expect:** 1SA 1:10-11, 1SA 1:27, PSA 113:9, GEN 25:21, PSA 34:18

### LIFE-18
- **Query:** we lost our baby
- **Expect:** 2SA 12:22-23, PSA 34:18, PSA 139:13-16, REV 21:4, MAT 5:4

### LIFE-19
- **Query:** I'm afraid of dying
- **Expect:** HEB 2:14-15, JHN 11:25-26, 1CO 15:55-57, PSA 23:4, PHP 1:21, JHN 14:1-3

### LIFE-20
- **Query:** I feel like nobody notices me
- **Expect:** PSA 139:13-14, MAT 10:29-31, ISA 43:4, EPH 2:10, 1PE 2:9, LUK 12:6-7

### LIFE-21
- **Query:** I hate the way I look
- **Expect:** PSA 139:14, 1SA 16:7, 1PE 3:3-4, PRO 31:30, GEN 1:27

### LIFE-22
- **Query:** my son stopped believing in God
- **Expect:** LUK 15:20, LUK 15:24, 2PE 3:9, PRO 22:6, 1TI 2:4, GAL 6:9

### LIFE-23
- **Query:** how do I share my faith with my friends
- **Expect:** 1PE 3:15, MAT 28:19-20, ROM 1:16, COL 4:5-6, ACT 1:8

### LIFE-24
- **Query:** I don't know what to do with my life
- **Expect:** PRO 3:5-6, JER 29:11, PSA 32:8, EPH 2:10, PRO 16:9, JAS 1:5

### LIFE-25
- **Query:** I lost my job
- **Expect:** MAT 6:31-33, PHP 4:19, PSA 37:25, ISA 41:10, 1PE 5:7

### LIFE-26
- **Query:** I'm in debt and can't pay my bills
- **Expect:** PRO 22:7, ROM 13:8, PSA 37:21, PHP 4:19, MAT 6:31-33, HEB 13:5

### LIFE-27
- **Query:** people at school make fun of me for being a Christian
- **Expect:** MAT 5:10-12, 1PE 4:14, 1PE 4:16, 2TI 3:12, JHN 15:18-19, ROM 1:16

### LIFE-28
- **Query:** I said something hurtful and want to make it right
- **Expect:** MAT 5:23-24, EPH 4:29, JAS 3:8-10, PRO 12:18, COL 4:6

### LIFE-29
- **Query:** my neighbor is really annoying
- **Expect:** MAT 22:39, ROM 12:18, COL 3:12-13, EPH 4:2-3, 1CO 13:4-5

### LIFE-30
- **Query:** I'm stressed about my exams
- **Expect:** PHP 4:6-7, COL 3:23, PRO 16:3, 1PE 5:7, JAS 1:5, MAT 11:28

### LIFE-31
- **Query:** I want to fast but don't know how
- **Expect:** MAT 6:16-18, ISA 58:6-7, JOL 2:12, ACT 13:2-3, ACT 14:23

### LIFE-32
- **Query:** I just became a Christian, what now
- **Expect:** 2CO 5:17, 1PE 2:2, ACT 2:42, 2TI 3:16-17, HEB 10:24-25, COL 2:6-7

### LIFE-33
- **Query:** church people are hypocrites
- **Expect:** MAT 23:27-28, MAT 7:3-5, ROM 3:23, HEB 10:24-25, JAS 1:22

### LIFE-34
- **Query:** should I get revenge
- **Expect:** ROM 12:17-21, PRO 20:22, PRO 24:29, MAT 5:38-39, 1TH 5:15, LEV 19:18

### LIFE-35
- **Query:** I want to be a better husband
- **Expect:** EPH 5:25, EPH 5:28-29, 1PE 3:7, COL 3:19, 1CO 13:4-7

### LIFE-36
- **Query:** I can't control what I eat
- **Expect:** 1CO 6:19-20, 1CO 10:31, PRO 23:20-21, PRO 23:2, GAL 5:22-23

### LIFE-37
- **Query:** I have too much to do and feel overwhelmed
- **Expect:** MAT 11:28-30, LUK 10:41-42, PSA 61:2, ISA 40:29-31, PSA 46:10, ECC 3:1

### LIFE-38
- **Query:** taking care of my elderly mother
- **Expect:** EXO 20:12, 1TI 5:4, 1TI 5:8, JHN 19:26-27, GAL 6:2, ISA 46:4

### LIFE-39
- **Query:** I'm angry at God
- **Expect:** PSA 13:1-2, PSA 13:5-6, PSA 73:21-26, JOB 42:2-3, ISA 55:8-9

### LIFE-40
- **Query:** why do bad things happen to good people
- **Expect:** ROM 8:28, JOB 1:21-22, JHN 9:2-3, JHN 16:33, ISA 55:8-9, ROM 8:18, JAS 1:2-4, 1PE 4:12-13

## Topics no guide covers

### NEW-01
- **Query:** envy
- **Expect:** PRO 14:30, PRO 23:17, PRO 24:1, JAS 3:14-16, GAL 5:26, 1CO 13:4, PSA 37:1, PSA 73:3

### NEW-02
- **Query:** patience
- **Expect:** JAS 1:3-4, ROM 12:12, GAL 5:22, EPH 4:2, COL 3:12, PSA 37:7, ECC 7:8, JAS 5:7-8, ROM 5:3-4

### NEW-03
- **Query:** self-control
- **Expect:** GAL 5:22-23, PRO 25:28, 2TI 1:7, TIT 2:11-12, 1CO 9:25-27, 2PE 1:5-6, PRO 16:32

### NEW-04
- **Query:** laziness
- **Expect:** PRO 6:6-11, PRO 13:4, PRO 19:15, PRO 20:4, PRO 24:30-34, PRO 26:14, 2TH 3:10-11

### NEW-05
- **Query:** friendship
- **Expect:** PRO 17:17, PRO 18:24, PRO 27:6, PRO 27:17, ECC 4:9-10, JHN 15:13, PRO 13:20

### NEW-06
- **Query:** justice
- **Expect:** MIC 6:8, AMO 5:24, ISA 1:17, PRO 21:15, PSA 89:14, DEU 16:20, PSA 82:3

### NEW-07
- **Query:** caring for the poor
- **Expect:** PRO 19:17, PRO 14:31, PRO 31:8-9, JAS 2:15-16, 1JN 3:17, DEU 15:11, ISA 58:10, GAL 2:10

### NEW-08
- **Query:** widows and orphans
- **Expect:** JAS 1:27, PSA 68:5, DEU 10:18, ISA 1:17, 1TI 5:3, EXO 22:22, PSA 146:9

### NEW-09
- **Query:** honesty
- **Expect:** PRO 12:22, EPH 4:25, COL 3:9, PRO 11:1, PRO 19:1, EXO 20:16, LEV 19:11, ZEC 8:16

### NEW-10
- **Query:** courage
- **Expect:** JOS 1:9, DEU 31:6, PSA 27:1, ISA 41:10, 1CO 16:13, 2TI 1:7, PSA 31:24

### NEW-11
- **Query:** hospitality
- **Expect:** HEB 13:2, ROM 12:13, 1PE 4:9, LUK 14:12-14, MAT 25:35, 3JN 1:5-8

### NEW-12
- **Query:** obeying God
- **Expect:** JHN 14:15, JHN 14:21, 1SA 15:22, JAS 1:22, DEU 28:1, LUK 11:28, 1JN 5:3, ACT 5:29

### NEW-13
- **Query:** the Holy Spirit
- **Expect:** JHN 14:26, JHN 16:13, ACT 1:8, ROM 8:26, GAL 5:22-23, 1CO 6:19, ACT 2:4, EPH 1:13-14

### NEW-14
- **Query:** the Trinity
- **Expect:** MAT 28:19, 2CO 13:14, JHN 1:1, JHN 10:30, JHN 14:16-17, 1PE 1:2, MAT 3:16-17, JHN 15:26

### NEW-15
- **Query:** angels
- **Expect:** HEB 1:14, PSA 91:11, PSA 34:7, HEB 13:2, LUK 15:10, MAT 18:10, PSA 103:20

### NEW-16
- **Query:** spiritual warfare
- **Expect:** EPH 6:10-18, 1PE 5:8-9, JAS 4:7, 2CO 10:3-5, JHN 10:10, 2CO 11:14, 1JN 4:4, REV 12:9

### NEW-17
- **Query:** the Sabbath
- **Expect:** EXO 20:8-11, GEN 2:2-3, MRK 2:27-28, HEB 4:9-10, ISA 58:13, COL 2:16-17, DEU 5:12-15

### NEW-18
- **Query:** wisdom
- **Expect:** JAS 1:5, PRO 1:7, PRO 9:10, PRO 2:6, PRO 3:13, PRO 4:7, COL 2:3, JAS 3:17, PSA 111:10

### NEW-19
- **Query:** thankfulness
- **Expect:** 1TH 5:18, PHP 4:6, PSA 100:4, COL 3:15-17, PSA 107:1, EPH 5:20, PSA 136:1

### NEW-20
- **Query:** joy
- **Expect:** NEH 8:10, PHP 4:4, PSA 16:11, JHN 15:11, ROM 15:13, GAL 5:22, JAS 1:2, PSA 30:5

### NEW-21
- **Query:** contentment
- **Expect:** PHP 4:11-13, 1TI 6:6-8, HEB 13:5, PRO 30:8-9, ECC 5:10, LUK 12:15

### NEW-22
- **Query:** peace
- **Expect:** JHN 14:27, PHP 4:7, ISA 26:3, ROM 5:1, COL 3:15, NUM 6:24-26, JHN 16:33, ROM 15:13

### NEW-23
- **Query:** hope
- **Expect:** ROM 15:13, JER 29:11, ROM 5:5, HEB 6:19, ROM 8:24-25, LAM 3:21-24, 1PE 1:3, PSA 42:11

### NEW-24
- **Query:** love your neighbor
- **Expect:** MAT 22:37-40, LEV 19:18, LUK 10:27, ROM 13:8-10, GAL 5:14, JAS 2:8, 1JN 4:20-21

### NEW-25
- **Query:** what love is
- **Expect:** 1CO 13:4-7, 1JN 4:7-8, 1JN 4:16, JHN 15:13, ROM 5:8, 1JN 3:16, JHN 3:16

### NEW-26
- **Query:** serving others
- **Expect:** MRK 10:45, GAL 5:13, PHP 2:3-4, JHN 13:14-15, MAT 20:26-28, 1PE 4:10, MAT 25:40

### NEW-27
- **Query:** spiritual gifts
- **Expect:** 1CO 12:4-11, ROM 12:6-8, 1PE 4:10-11, EPH 4:11-12, 1CO 12:27-31, 1CO 14:1

### NEW-28
- **Query:** communion
- **Expect:** 1CO 11:23-26, LUK 22:19-20, MAT 26:26-28, 1CO 10:16-17, 1CO 11:27-29, MRK 14:22-24

### NEW-29
- **Query:** the resurrection of Jesus
- **Expect:** 1CO 15:3-8, 1CO 15:14, 1CO 15:17, MAT 28:5-7, LUK 24:6, ROM 6:9, ROM 10:9, JHN 20:27-29

### NEW-30
- **Query:** the Ten Commandments
- **Expect:** EXO 20:1-17, DEU 5:6-21, MAT 22:36-40, ROM 13:9

### NEW-31
- **Query:** sex before marriage
- **Expect:** 1CO 6:18-20, 1TH 4:3-5, HEB 13:4, 1CO 7:2, 1CO 7:8-9, EPH 5:3, GAL 5:19

### NEW-32
- **Query:** lying
- **Expect:** PRO 12:22, PRO 6:16-19, COL 3:9, EPH 4:25, EXO 20:16, JHN 8:44, REV 21:8, PRO 19:5

### NEW-33
- **Query:** watching what you say
- **Expect:** JAS 3:5-10, PRO 18:21, PRO 21:23, EPH 4:29, MAT 12:36-37, PSA 141:3, COL 4:6, PRO 10:19, JAS 1:26

### NEW-34
- **Query:** racism
- **Expect:** GAL 3:28, ACT 10:34-35, ACT 17:26, REV 7:9, COL 3:11, JAS 2:1, JAS 2:9, GEN 1:27

### NEW-35
- **Query:** faith without works
- **Expect:** JAS 2:14-17, JAS 2:26, EPH 2:10, MAT 5:16, TIT 2:14, TIT 3:8, GAL 5:6

### NEW-36
- **Query:** the fear of the Lord
- **Expect:** PRO 1:7, PRO 9:10, PSA 111:10, ECC 12:13, PRO 14:27, PSA 34:9, PRO 19:23

### NEW-37
- **Query:** taking care of animals and the earth
- **Expect:** GEN 1:26, GEN 1:28, GEN 2:15, PSA 24:1, PRO 12:10, PSA 50:10-11, MAT 6:26, PSA 104:24

### NEW-38
- **Query:** the armor of God
- **Expect:** EPH 6:10-18, 1TH 5:8, ROM 13:12, 2CO 10:4

### NEW-39
- **Query:** repentance
- **Expect:** ACT 3:19, ACT 2:38, 2CO 7:10, LUK 15:7, LUK 15:10, 2PE 3:9, 1JN 1:9, LUK 13:3, MAT 4:17

### NEW-40
- **Query:** God's faithfulness
- **Expect:** LAM 3:22-23, DEU 7:9, 1CO 10:13, 2TI 2:13, PSA 36:5, HEB 10:23, 1TH 5:24, PSA 89:1

## Stories and passages

### STORY-01
- **Query:** the good Samaritan
- **Expect:** LUK 10:30-37

### STORY-02
- **Query:** Jesus calms the storm
- **Expect:** MRK 4:35-41, MAT 8:23-27, LUK 8:22-25

### STORY-03
- **Query:** David and Goliath
- **Expect:** 1SA 17:4, 1SA 17:32, 1SA 17:45-50

### STORY-04
- **Query:** the prodigal son
- **Expect:** LUK 15:11-32

### STORY-05
- **Query:** Jesus feeds five thousand people
- **Expect:** MAT 14:15-21, MRK 6:35-44, LUK 9:12-17, JHN 6:5-13

### STORY-06
- **Query:** Daniel in the lions' den
- **Expect:** DAN 6:16-23

### STORY-07
- **Query:** Noah and the flood
- **Expect:** GEN 6:13-22, GEN 7:11-24, GEN 8:1, GEN 9:11-17

### STORY-08
- **Query:** Jesus walks on water
- **Expect:** MAT 14:25-33, MRK 6:47-51, JHN 6:16-21

### STORY-09
- **Query:** Jesus washes the disciples' feet
- **Expect:** JHN 13:3-15

### STORY-10
- **Query:** Moses parts the Red Sea
- **Expect:** EXO 14:21-31

### STORY-11
- **Query:** Jonah and the big fish
- **Expect:** JON 1:17, JON 2:1-10

### STORY-12
- **Query:** Jesus raises Lazarus from the dead
- **Expect:** JHN 11:38-44, JHN 11:25-26

### STORY-13
- **Query:** the birth of Jesus
- **Expect:** LUK 2:1-20, MAT 1:18-25, MAT 2:1-12

### STORY-14
- **Query:** Zacchaeus climbs a tree
- **Expect:** LUK 19:1-10

### STORY-15
- **Query:** the woman caught in adultery
- **Expect:** JHN 8:3-11

### STORY-16
- **Query:** Abraham is told to sacrifice Isaac
- **Expect:** GEN 22:1-14

### STORY-17
- **Query:** the Tower of Babel
- **Expect:** GEN 11:1-9

### STORY-18
- **Query:** the day of Pentecost
- **Expect:** ACT 2:1-41

### STORY-19
- **Query:** Saul on the road to Damascus
- **Expect:** ACT 9:1-19

### STORY-20
- **Query:** Jesus turns water into wine
- **Expect:** JHN 2:1-11

### STORY-21
- **Query:** Adam and Eve eat the forbidden fruit
- **Expect:** GEN 3:1-24

### STORY-22
- **Query:** Joseph forgives his brothers
- **Expect:** GEN 45:1-15, GEN 50:15-21

### STORY-23
- **Query:** Ruth stays with Naomi
- **Expect:** RUT 1:14-18

### STORY-24
- **Query:** the parable of the sower
- **Expect:** MAT 13:3-23, MRK 4:3-20, LUK 8:5-15

### STORY-25
- **Query:** the beatitudes
- **Expect:** MAT 5:3-12, LUK 6:20-23

## Other languages

### LANG-01
- **Query:** Ich bin neidisch auf meinen Bruder
- **Language:** de
- **Expect:** PRO 14:30, JAS 3:14-16, GAL 5:26, 1CO 13:4, PRO 23:17

### LANG-02
- **Query:** Geduld
- **Language:** de
- **Expect:** JAS 1:3-4, ROM 12:12, GAL 5:22, EPH 4:2, ECC 7:8, JAS 5:7-8

### LANG-03
- **Query:** der barmherzige Samariter
- **Language:** de
- **Expect:** LUK 10:30-37

### LANG-04
- **Query:** perdí mi trabajo
- **Language:** es
- **Expect:** MAT 6:31-33, PHP 4:19, PSA 37:25, ISA 41:10, 1PE 5:7

### LANG-05
- **Query:** la amistad verdadera
- **Language:** es
- **Expect:** PRO 17:17, PRO 18:24, PRO 27:6, PRO 27:17, ECC 4:9-10, JHN 15:13

### LANG-06
- **Query:** Jesús calma la tormenta
- **Language:** es
- **Expect:** MRK 4:35-41, MAT 8:23-27, LUK 8:22-25

### LANG-07
- **Query:** j'ai peur de mourir
- **Language:** fr
- **Expect:** HEB 2:14-15, JHN 11:25-26, 1CO 15:55-57, PSA 23:4, PHP 1:21, JHN 14:1-3

### LANG-08
- **Query:** l'honnêteté
- **Language:** fr
- **Expect:** PRO 12:22, EPH 4:25, COL 3:9, PRO 11:1, PRO 19:1, EXO 20:16, LEV 19:11

### LANG-09
- **Query:** le fils prodigue
- **Language:** fr
- **Expect:** LUK 15:11-32

### LANG-10
- **Query:** não consigo dormir à noite
- **Language:** pt-BR
- **Expect:** PSA 4:8, PSA 127:2, PRO 3:24, PSA 3:5

### LANG-11
- **Query:** o Espírito Santo
- **Language:** pt-BR
- **Expect:** JHN 14:26, JHN 16:13, ACT 1:8, ROM 8:26, GAL 5:22-23, 1CO 6:19, ACT 2:4

### LANG-12
- **Query:** Davi e Golias
- **Language:** pt-BR
- **Expect:** 1SA 17:4, 1SA 17:32, 1SA 17:45-50

### LANG-13
- **Query:** anak saya tidak mau mendengarkan saya
- **Language:** id
- **Expect:** EPH 6:4, PRO 22:6, PRO 29:17, COL 3:21, DEU 6:6-7

### LANG-14
- **Query:** sukacita
- **Language:** id
- **Expect:** NEH 8:10, PHP 4:4, PSA 16:11, JHN 15:11, ROM 15:13, GAL 5:22

### LANG-15
- **Query:** Yesus berjalan di atas air
- **Language:** id
- **Expect:** MAT 14:25-33, MRK 6:47-51, JHN 6:16-21

### LANG-16
- **Query:** nagsinungaling ako sa mga magulang ko
- **Language:** fil
- **Expect:** 1JN 1:9, PRO 28:13, PSA 32:5, EPH 4:25, PRO 12:22, COL 3:9

### LANG-17
- **Query:** pagpapasalamat
- **Language:** fil
- **Expect:** 1TH 5:18, PHP 4:6, PSA 100:4, COL 3:15-17, PSA 107:1, EPH 5:20

### LANG-18
- **Query:** si Jonas at ang malaking isda
- **Language:** fil
- **Expect:** JON 1:17, JON 2:1-10

### LANG-19
- **Query:** nina madeni mengi na siwezi kulipa
- **Language:** sw
- **Expect:** PRO 22:7, ROM 13:8, PSA 37:21, PHP 4:19, MAT 6:31-33, HEB 13:5

### LANG-20
- **Query:** haki
- **Language:** sw
- **Expect:** MIC 6:8, AMO 5:24, ISA 1:17, PRO 21:15, PSA 89:14, DEU 16:20

### LANG-21
- **Query:** Danieli katika tundu la simba
- **Language:** sw
- **Expect:** DAN 6:16-23

### LANG-22
- **Query:** 직장 동료가 내 공을 가로채요
- **Language:** ko
- **Expect:** COL 3:23-24, PSA 37:7-8, ROM 12:19, 1PE 2:19-23

### LANG-23
- **Query:** 성령
- **Language:** ko
- **Expect:** JHN 14:26, JHN 16:13, ACT 1:8, ROM 8:26, GAL 5:22-23, 1CO 6:19, ACT 2:4

### LANG-24
- **Query:** 탕자의 비유
- **Language:** ko
- **Expect:** LUK 15:11-32

### LANG-25
- **Query:** 我很嫉妒别人
- **Language:** zh-Hans
- **Expect:** PRO 14:30, JAS 3:14-16, GAL 5:26, 1CO 13:4, PRO 23:17

### LANG-26
- **Query:** 智慧
- **Language:** zh-Hans
- **Expect:** JAS 1:5, PRO 1:7, PRO 9:10, PRO 2:6, PRO 4:7, JAS 3:17

### LANG-27
- **Query:** 耶稣给门徒洗脚
- **Language:** zh-Hans
- **Expect:** JHN 13:3-15
