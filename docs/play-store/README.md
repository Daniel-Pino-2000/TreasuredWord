# Google Play store listing

The text and graphics of TreasuredWord's default (en-US) store listing in Play Console
(**Grow users → Store presence → Store listings → Default store listing**). Change them here and
in Play Console together, so the repo matches what users see.

## Text

**App name** (30 max): TreasuredWord

**Short description** (80 max):

> Read the Bible offline. Highlight verses, write notes, sync across devices.

**Full description** (4000 max):

```text
TreasuredWord is a free Bible reading app with no ads and no tracking.

READ
• Download free Bible translations in many languages and read them offline.
• Pick up where you left off, and keep a bookmark in your place.
• Search the Bible.
• A verse of the day, with an optional daily reminder.

STUDY
• Highlight verses in five colors.
• Write notes on one verse or several.
• "About this book" links each book to its GotQuestions.org summary and its BibleProject overview video.

YOURS
• No account needed: without one, everything you create stays on your phone.
• Or create a free account to sync your highlights, notes and reading position between devices. You can delete it at any time from inside the app.
• Light and dark themes, adjustable text size, and a layout that works in landscape.
• Available in English, Spanish, Portuguese, French, German, Indonesian, Filipino, Swahili, Korean and Simplified Chinese.

Bible translations come from bible.helloao.org and the verse of the day from OurManna. GotQuestions.org and BibleProject content opens on their own sites and apps; TreasuredWord isn't affiliated with either.
```

Third-party sources are named and the listing says there's no affiliation, as `CLAUDE.md`
requires for GotQuestions.org and BibleProject. Avoid promotional words like "best" or "#1" in
the graphics; Play's metadata policy rejects them.

## Graphics

| Slot | File | Size |
|---|---|---|
| App icon | `../icon-source/playstore-icon-512.png` | 512×512 |
| Feature graphic | `feature-graphic.png` | 1024×500 |
| Phone screenshots | `screenshots/1-home.png` … `7-dark-theme.png`, uploaded in that order | 1080×1920 |

Play only accepts phone screenshots in a 16:9 or 9:16 ratio, so the full-height S23 size
(1080×2340) is rejected. The screenshots were taken in October 2026 on an emulator set to
`wm size 1080x1920` with the status bar in demo mode (`sysui_demo_allowed`, clock 9:00). They
use the KJV with sample highlights and a sample note in Psalm 23. At least four screenshots of
1080 px or more on each side keep the app eligible for Play's promotions.

The feature graphic is the app icon on a dark background matching the icon's, with the name in
Georgia Bold and the tagline in Segoe UI Light.
