# Bible licensing: what TreasuredWord offers, why, and what it owes

This is the record of every decision about where TreasuredWord's Bible text comes from, the
licenses behind it, and the duties those licenses bring. **Read it before adding, removing or
changing a Bible version, adding AI or sharing features that touch Bible text, or adding anything
that costs money (donations, ads, purchases).** Issue #366 has the history.

Last reviewed: 2026-11-21.

## Where the text comes from

| Source | Versions | License | Offline |
|---|---|---|---|
| Bundled in the app | KJV | Public domain | Always |
| [bible.helloao.org](https://bible.helloao.org) | 1,500+ public-domain and openly licensed translations (RV1909, BSB, WEB…) | Each translation's own, shown in the reader's credit (#98) | Downloaded whole |
| [YouVersion Platform](https://developers.youversion.com) | **LBLA, NBLA, NASB 2020, NASB 1995, AMP** (Lockman) | Lockman Fast-track license, accepted 2026-11-21 | Each chapter is fetched the first time it's opened, then kept on the phone |

The list of YouVersion versions is `app/src/main/java/com/application/bibleapp/data/model/YouVersionBibles.kt`.
A version may be added there only after its publisher's license is accepted in the portal and
recorded below. A YouVersion version taken off that list is deleted from phones at startup
(`YouVersionBibles.isWithdrawn`, #381), so removing one there is all an update needs to do.

## The YouVersion Platform account

- **Registered:** 2026-11-21, by the developer as an **Individual** ("Daniel Pino",
  profit designation Non Profit) at [platform.youversion.com](https://platform.youversion.com).
  The name and postal address given there are shared with each publisher whose Bible is used, to
  check eligibility.
- **App:** "TreasuredWord", **Non-commercial**, status **Live** since 2026-11-21, website = the privacy
  policy, Google Play URL set. Three confirmations were ticked: truly non-commercial and free with
  nothing behind a paywall; no advertisements; used to help people engage with the Bible in a way
  consistent with YouVersion's [Core Beliefs](https://platform.youversion.com/core-beliefs) (the
  Apostles' and Nicene Creed basics).
- **Made Live** on 2026-11-21, by the developer (#374). The portal says going live raises the API
  rate limits to "production based limits"; it asked only for the app's name and where it can be
  found, and started no review. The app name there is used in YouVersion's reporting.
- **The App Key** is a secret under the Platform terms ("keep confidential and not share with any
  third-party"):
  - local builds: `YOUVERSION_APP_KEY` in the git-ignored `local.properties`;
  - CI: the `YOUVERSION_APP_KEY` repository secret (App CI passes it to Gradle);
  - it reaches the app through `BuildConfig.YOUVERSION_APP_KEY`. A key inside an APK can be
    extracted; YouVersion's own Kotlin SDK puts it in client code the same way, and recommends no
    server proxy. If it leaks or is abused, create a new key in the portal, update both places,
    and release an update.
  - Without a key, the build still works and simply doesn't offer these versions.
- **Rate limit:** per key, not published (raised once the app went Live). On HTTP 429 the reader shows a calm "try again in a
  minute" message (`ChapterUnavailableMessage`). Whole Bibles are never fetched at once (1,189
  calls per reader could lock out every reader). A chapter is fetched when opened, and while a
  licensed version is read online the app reads ahead slowly in the background (#384,
  `LicensedReadAheadWorker`): the rest of the book, then the next books, one chapter about every
  15 s, on Wi-Fi or another unmetered network only, at most 100 chapters per phone per day. A 429
  (to the reader or the read-ahead) stops it until `Retry-After` has passed (5 minutes if it
  doesn't say) and doubles its pause, up to 4 minutes, back to 15 s the next day. Removing the
  version cancels it. The numbers live in `ReadAheadLimits` (`data/repository/LicensedReadAhead.kt`);
  raise them only if 429s turn out to be rare. A one-tap "download whole version" would come
  after that, if ever. The license allows it: Lockman's text may reach the phone "by live data
  connection or download to the user's device".

### YouVersion Platform Terms of Use (last modified 2026-08-17), accepted 2026-11-21

What we agreed to, and how the app keeps to it:

| Term | What it means for TreasuredWord |
|---|---|
| Free license to use the tools, revocable; either side can end it with 30 days' notice; YouVersion can suspend at once | If it ends, remove the YouVersion versions in an update and delete saved chapters |
| Keep the key confidential | See above |
| Keep any usage reporting built into the tools enabled | We call the REST API directly, so there's no SDK reporting to switch on. If we ever adopt the SDK, leave its reporting on |
| No use of YouVersion's names or logos ("YouVersion", "Bible App", "Life.Church"), and nothing implying partnership | The app's screens never name YouVersion; the credit names the publisher. The privacy policy names YouVersion only as where data goes, which the law requires |
| No reverse engineering, no competing service built from their tools | — |
| **AI: the tools may not be used with AI for open-ended chat, except to retrieve and display Scripture word for word, unaltered; no AI training** | Licensed text is **shown, never sent** (#383, rechecked 2026-11-21). Bible Helper's verse cards, search by meaning's results and Key passages show the reader's licensed version word for word (`loadAiVersePassage`, `resolveMeaningVerses`), fetching an unsaved chapter like the reader; offline or on a 429 a card falls back to the KJV whole, and a results list to the KJV whole. "Ask about this verse" sends the AI only the reference and the version's name (`AiQuestionPassage.dto`); the server quotes the public-domain BSB to the model instead. Licensed text never reaches the server, its logs, evaluations or any AI training. Sending it to the AI would need YouVersion's written approval, which we're not asking for |
| Install security updates YouVersion flags | Watch YouVersion's notices (portal **Notifications**, the developer's email) |
| If the app ever charges money, tell users the YouVersion Bible App is free | Not applicable: the app is free and must stay so while these versions are offered |
| Oklahoma law, no class actions, we indemnify YouVersion | — |

## Lockman Fast-track Bible License v1 (accepted 2026-11-21)

Covers LBLA, NBLA, NASB 2020, NASB 1995 and AMP. Full text: [Google Doc](https://docs.google.com/document/d/1DOsHZz5GzDs0L9Yy5BTU7IdANPmzTtXuQuI-TVkg0VY/edit).
It takes effect after YouVersion vets the app, and either Lockman or YouVersion can end it with
30 days' notice. It ends by itself if a condition stops being met. California law.

| Condition | How the app meets it |
|---|---|
| Text only in an app that gets it through the YouVersion Platform | `YouVersionBibleSource` |
| "Live data connection or download to the user's device" | Chapters are kept on the phone after they're read, and the next ones are read ahead slowly on Wi-Fi (#384) |
| Downloading a large part for use outside the app must be difficult | Text stays in the app's private database; nothing exports a book or more |
| Completely free: no charges, no membership fees, **no third-party advertising** | The app's rule; see Donations below |
| A clickable link to lockman.org and the license's copyright notice, conspicuous | The chapter credit shows the notice word for word (`YouVersionBibles.kt`) and a lockman.org link |
| Quoted verses: the version's tag after them, linked to lockman.org | Copy, Share and the daily verse share add `https://www.lockman.org` after the reference. Next to AI answers (#383), the tag on each verse card, search-by-meaning result, Key passage and asked-about passage is a link to lockman.org (`appendVersionTag`) |
| **AI usage** (Lockman's [permission page](https://www.lockman.org/permission-to-quote-copyright-trademark-information/), "AI Usage & Attribution"): AI apps may quote up to 1,000 verses per answer for search, citation, RAG and grounding, with the version's tag; no training | Within it: cards show at most an answer's cited passages, search by meaning at most 20 verses, each tagged. The text is never used for training, and isn't sent to the AI at all (YouVersion's stricter rule above) |
| Promote Lockman, e.g. by the link | The link above |
| Notify Lockman of website URLs | Not applicable (app only). Do it if a website ever shows the text |
| Trademarks (LBLA, NBLA, NASB, AMP) only to name the version | Only as version labels |
| **Every year, by the end of February, report how many copies were distributed the previous calendar year** | See "Yearly duties" |

## MissionAssist Fast-track Bible License v1 (accepted and withdrawn 2026-11-21)

It covered the EasyEnglish Bible 2024. Full text: [Google Doc](https://docs.google.com/document/d/1EQdlm24Y419Lb38Ih5_GxQRGKaZ-FIaVicTG5zLDDII/edit).
EasyEnglish was taken out before it shipped (#381): this license bars using the text "to produce
personalized content through AI", so it couldn't join the Lockman versions in Bible Helper and
search-by-meaning answers, and one version wasn't worth a different experience.

**The license stays accepted, unused (decided 2026-11-21).** The portal has no way to deselect an
accepted license: "Your licenses" lists it read-only, and the app's settings have no choice of
Bibles. It binds only use of the text, which the app no longer makes, so it costs nothing. To end it
anyway, send 30 days' written notice to agreements@easyenglish.bible (its section 5).

BroadStreet, Wycliffe and likely SIL, Biblion, Hawaii Pidgin Bible and EIB use the same template.
Before accepting any of them, know what it asks:

- Royalty-free display, storage and distribution in the app; users may read it online and offline.
- No edits to the text, which is used in the form the API gives. **All footnotes** must be included
  and accessible.
- "Industry standard encryption" against unauthorized copying. Android's device encryption and the
  app's private storage may be enough; if not, SQLCipher is free.
- **No use of the text to produce personalized content through AI**, which keeps it out of Bible
  Helper and search by meaning, including their verse cards.
- No printing. Cross-marketing is allowed both ways without implying sponsorship.
- They can end it at any time by written notice; we by 30 days' notice (the license also names
  deselecting it in the portal, which the portal doesn't offer for an accepted license).
  Oklahoma law. They may receive aggregate, de-identified usage statistics.

## Decided against, or deferred

| Version(s) | Decision (2026-11-21) | Why | What it would take |
|---|---|---|---|
| **RVR1960** (Sociedades Bíblicas Unidas) | Not offered | Not on the YouVersion Platform for other apps, only in YouVersion's own Bible App | A direct license from the Bible Societies (American Bible Society in the US), usually paid. Must be free. **The developer decided on 2026-11-21 not to write to publishers for now** (#376, closed); the yearly recheck below covers whether any of these join the Platform |
| **NTV, NLT** (Tyndale) | Not offered | Not on the Platform | A direct license from Tyndale House. Not requested (#376) |
| **NKJV** (Thomas Nelson / HarperCollins) | Not offered | Not on the Platform | A direct license from HarperCollins Christian Publishing. Not requested (#376) |
| **ESV** (Crossway) | Not offered yet | Not on the Platform | The free, non-commercial [ESV API](https://api.esv.org): 5,000 queries a day, at most 500 verses stored offline, has chapter audio (#377) |
| **NIV, NIrV, NVI** (Biblica) | Deferred | Biblica's agreement is heavy for a fast-changing app: Biblica's written approval before every new or enhanced version of the app, a display limit, footnotes, encryption, a yearly audit, shipping within 12 months, destroying copies within 72 hours if it ends, Colorado arbitration. **Its terms are confidential once accepted**, so they're not summarized here | **Not now** (developer, 2026-11-21; #375 closed). Accept it in the portal only after the app's pace slows, ideally after asking Biblica in writing how the approval clause applies |
| **The Passion Translation** (BroadStreet) | Not offered | A loose paraphrase by one translator that adds wording, widely criticized; YouVersion lists it "In Review", 51 books | — |
| **Wycliffe** English/Spanish (Plain English Version, Gloss Spanish) | Not offered | Partial Bibles (29 and 39 books); the reader supports complete 66-book Bibles | Accepting Wycliffe's license would also unlock ~1,000 Bibles in other languages, worth a look for the app's other languages (#379) |
| **SIL, Biblion, Hawaii Pidgin, EIB** | Not offered | No English or Spanish Bibles | Same as above, per language |
| **EasyEnglish 2024** (MissionAssist) | Removed before release (#381) | Its license bars AI use of the text (see above), so it would be the one licensed version without verse cards in AI answers | — |

Doctrinal check for any future version: the translation must be a faithful, complete Bible from a
recognized Christian translation team (not one person's paraphrase), and fit YouVersion's Core
Beliefs, which the app confirmed to YouVersion.

## Donations, ads and purchases

**Decision (2026-11-21): no donations, ads, purchases or subscriptions while the licensed versions
are offered.** Lockman requires distribution "completely free of charge" with no third-party ads;
the Platform terms and app registration say non-commercial; Biblica's license forbids using its
text in connection with soliciting contributions. Before adding any of these, remove the licensed
versions or get each publisher's written permission. API.Bible and the ESV API also count
fundraising as commercial use.

## Privacy

Reading a YouVersion version sends YouVersion the translation and chapter, the app's key and the
device's IP address, never account data. Word search in one sends the words searched (#382).
`docs/privacy-policy.md` says so (effective 2026-11-21).
The Play Console **Data safety** form must list it: data shared with a third party is none of the
user's personal data beyond the IP address that every web request carries, so it's the same answer
as for bible.helloao.org, but recheck the form whenever this changes.

## Yearly duties

| When | What | Where |
|---|---|---|
| **By the end of February** | Lockman: report the number of copies distributed in the previous calendar year. Use Play Console → Statistics → new installs ("device acquisitions") for 1 January–31 December. Send it through Lockman's [Email Us form](https://www.lockman.org/email-us/), or by post to The Lockman Foundation, PO Box 2279, La Habra, CA 90632-2279. Note the date sent in the reminder issue | A workflow (`.github/workflows/lockman-annual-report.yml`) opens a reminder issue every 1 February |
| Once a year (with the report) | Recheck this file: Platform terms, each license's text (links above), whether RVR1960/NTV/NLT/NKJV/ESV became available on the Platform, the notices in `YouVersionBibles.kt` | — |
| When YouVersion or a publisher writes | Apply notice changes or security updates; answer termination notices by removing the version | — |

## Technical notes

- `YouVersionBibleSource`: `GET /v1/bibles/{id}/index` once per version (book names and verse
  counts), `GET /v1/bibles/{id}/passages/{BOOK.CHAPTER}?format=html` per chapter, `GET /v1/bibles/{id}`
  for the copyright line when the license prescribes none.
- `YouVersionHtmlParser` turns the HTML into the same verses and footnotes as a helloao download.
  Its tests use made-up text: **never copy licensed Scripture into this public repository**, not
  even as test fixtures.
- Saved chapters keep the shape the parser gave them. If the parser changes how it reads the HTML,
  clear the saved chapters of the YouVersion versions in the same release (delete their rows in
  `downloaded_verses`/`downloaded_footnotes`; the book list stays), so they're fetched again.
- Word search in a YouVersion version uses `GET /v1/search-verses?bible_id=…&query=…&user_intent=text`
  (#382), which returns references only, most relevant first, 25 a page. `LicensedWordSearch`
  resolves 8 at a time as the list scrolls: from saved chapters, else by fetching and saving the
  chapter as the reader does. YouVersion lists verses that only relate to the words after the ones
  that have them; those are dropped, matched as the phone's own search matches. Offline, or on a 429
  or other failure, it searches the saved chapters instead and says so (#378). The license texts
  put no limit on search, and YouVersion offers this endpoint for licensed Bibles.
