<!--
  DRAFT — written from what the code does as of 2026-10-22 (app + server/). Before publishing:
  fill in the contact email and effective date, have it reviewed, and host it at a public URL
  (Google Play asks for one). Update it whenever the app starts sending data somewhere new.
-->

# TreasuredWord privacy policy

**Effective date:** [date]

TreasuredWord is a Bible reading app. This policy explains what information the app uses, where it goes, and the choices you have.

## The short version

- You can use TreasuredWord without an account. Your highlights, notes, bookmark and settings then stay on your phone.
- There are no ads, no analytics and no tracking in the app.
- If you create an account, we store your email address, a scrambled form of your password, and your highlights, notes and reading position so they sync between your devices. You can delete the account and all of it from inside the app.
- Some content comes from other websites, and some links open other services. Those services see that visit.

## Using the app without an account

Everything you create (highlights, notes, your bookmark, reading position, theme, text size, language and reminder settings) is stored only on your device.

## If you create an account

When you sign up and sync, our server stores:

- **Your email address**, to identify your account and let you sign in.
- **Your password**, only as a one-way hash (bcrypt). We never store or see the password itself.
- **Your highlights:** which verses, which translation, and the color.
- **Your notes:** the text you write and the verses it's attached to.
- **Your reading position:** translation, book, chapter and verse.
- **Sign-in tokens**, so you stay signed in. On your phone they're encrypted with a key that never leaves the device.

We use this information only to sync your data between your devices. We don't sell it, share it for advertising, or use it for anything else.

The server runs on [Render](https://render.com) in the United States, and the data is stored in a [Neon](https://neon.tech) PostgreSQL database. Like most web services, the server and its hosting provider keep short-lived technical logs of requests (for example, the time and which address was requested) to keep the service running.

When you delete a highlight or note, the server marks it as deleted, so your other devices remove it too. It's erased completely when you delete your account.

### Deleting your account

You can delete your account at any time: open **Settings** (the gear on the You tab), and under **Account** tap **Delete account**. This permanently erases your account and everything synced to it from our server. Highlights and notes already on your phone stay there until you remove the app.

## Content the app downloads

To show Bible text and the verse of the day, the app downloads content from:

- **[bible.helloao.org](https://bible.helloao.org)**: Bible translations you choose to download.
- **[OurManna](https://ourmanna.com)**: the daily verse.

These requests carry no account information. Like any web request, they let those services see your device's internet (IP) address.

## Outside links and videos

TreasuredWord links to resources made by other ministries. They open outside the app:

- **GotQuestions.org** answers and book summaries open in your phone's browser (shown as a browser sheet over the app). GotQuestions.org and your browser's provider see that visit.
- **BibleProject** overview videos open in the YouTube app, or on youtube.com if the app isn't installed. Google (YouTube) sees what you watch.

The app sends these services no account information and doesn't keep a record of what you open. Your visit is covered by their own privacy policies: [GotQuestions.org](https://www.gotquestions.org/privacy.html), [Google](https://policies.google.com/privacy) and your browser's.

## Backups and new phones

If Android backup is turned on, Android copies your TreasuredWord settings (reading position, bookmark, theme, text size, reminder and language) to your Google account backup. When you move to a new phone with a cable or direct transfer, Android also copies the highlights, notes and translations stored on the phone. Your sign-in is never copied; you sign in again on the new phone.

## Notifications

The daily verse reminder is scheduled on your phone. Turning it on or off doesn't send anything to us.

## Changes to this policy

If this policy changes, we'll update it here and change the effective date above.

## Contact

Questions about privacy: [contact email]
