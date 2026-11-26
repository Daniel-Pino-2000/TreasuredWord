---
title: Privacy policy
permalink: /privacy/
---

# TreasuredWord privacy policy

**Effective date:** November 26, 2026

TreasuredWord is a free Bible reading app made by Daniel Pino, an independent developer. In this policy, "we" and "us" mean Daniel Pino, the developer of TreasuredWord. It explains what information the app uses, where it goes, and the choices you have. If you have questions, email [pinoponedaniel@gmail.com](mailto:pinoponedaniel@gmail.com).

## The short version

- You can use TreasuredWord without an account. Your highlights, notes, the verses you're memorizing, bookmark and settings then stay on your phone.
- There are no ads, no analytics and no tracking in the app. We don't sell or share your information.
- If you create an account, we store your email address, a scrambled form of your password (or, if you sign in with Google, your Google account ID instead), and your highlights, notes, the verses you're memorizing with your practice progress, and reading position so they sync between your devices. You can delete the account and all of it from inside the app, or by emailing us.
- We email you only when you ask for it: to check that your email address is yours, or to reset a forgotten password. Those emails are sent through Gmail, from TreasuredWord's own Gmail account.
- If you ask the Bible Helper a question, it's sent with the recent conversation to our server and to Cloudflare, whose AI writes the answer. Cloudflare doesn't keep it or train on it.
- When you search the Bible and press the search button, what you typed is also sent, without your account, to our server and to Cloudflare to find verses related by meaning. Neither keeps it, unless you choose to send us a search whose results didn't help.
- If you read one of the licensed Bible translations (such as LBLA, NBLA, NASB or AMP), the app gets each chapter from YouVersion the first time you open it, and a word search in one of them is done by YouVersion. YouVersion sees which chapter, and the words you search, but not your account.
- Some content comes from other websites, and some links open other services. Those services see that visit.

## Using the app without an account

Everything you create (highlights, notes, the verses you're memorizing and your practice progress, your bookmark, reading position, theme, text size, language and reminder settings) is stored only on your device.

## If you create an account

Creating an account is optional. When you sign up and sync, our server stores:

- **Your email address**, to identify your account and let you sign in.
- **Your password**, only as a one-way hash (bcrypt). We never store or see the password itself. Accounts made with Google sign-in have no password.
- **Your Google account ID**, if you use "Continue with Google" (see below).
- **Your highlights:** which verses, which translation, and the color.
- **Your notes:** the text you write and the verses it's attached to.
- **Verses you're memorizing and your practice progress:** which verses, which translation, when you added them, and how far along you are (for example your level, when you last practiced and when each one is next due).
- **Your reading position:** translation, book, chapter and verse.
- **Sign-in tokens**, so you stay signed in. The server keeps only a hash of each one, and on your phone they're encrypted with a key that never leaves the device.
- **Whether your email address is verified**, and the one-time codes we email you (see below), kept only as a scrambled form (a keyed hash) and deleted within a day.

### Emails we send

We email you a 6-digit code, which you type into the app, in two cases:

- **To check that your email address is yours**, which the app offers after you create an account with a password.
- **To reset a forgotten password**, when you tap **Forgot password?** on the sign-in screen. Resetting it signs you out on all your devices.

We send these emails only when you ask for them in the app, and we never send newsletters or advertising.

The email is sent through Gmail, Google's email service, from a Gmail account that belongs to TreasuredWord. To deliver it, Google receives your email address and the email itself, which contains the code, as described in [Google's privacy policy](https://policies.google.com/privacy). Gmail keeps a copy in that account's sent mail, which only we can open; the code in it stops working after 15 minutes. If you sign in with Google, your address is already checked by Google, so we don't need to email you to check it.

### Signing in with Google

If you choose **Continue with Google**, Google's own sign-in screen on your phone asks which Google account to use, and Google gives the app a signed token with that account's email address and its Google account ID. The app sends the token to our server, which checks with Google's published keys that it's genuine and then signs you in. We store the Google account ID (a number Google assigns, which identifies the account to us but not to anyone else) so we recognize you next time, and the email address as for any account. If you already had an account with the same email, the two are joined into one. We don't receive your Google password, contacts or anything else from your Google account, and we don't use the token for anything but signing you in. Google learns that you signed in to TreasuredWord; that's covered by [Google's privacy policy](https://policies.google.com/privacy).

Google also gives the app the address of the account's profile picture. The app keeps that address on your phone only, never on our server, and downloads the picture from Google's image server (lh3.googleusercontent.com) to show it on the **You** tab. The picture is kept on the phone so it also shows offline, is never included in backups, and is removed when you sign out or delete your account. Like any web request, downloading it lets Google see your device's internet (IP) address. Accounts made with an email and password have no picture, and the app doesn't download one for them.

We use this information only to provide the sync you asked for by creating an account. We don't use it for advertising, and we don't share it with anyone except the hosting providers below, who store it on our behalf, and Google, which delivers the emails you ask for.

The server runs on [Render](https://render.com) in the United States, and the data is stored in a [Neon](https://neon.tech) PostgreSQL database. If you live outside the United States, your information is transferred to and stored there. Like most web services, the server and its hosting provider keep technical logs of requests (for example, the time, the address requested and the requesting IP address) to run and protect the service.

When you delete a highlight or note, or remove a verse from your Memorize list, the server keeps it marked as deleted, so your other devices remove it too. It's erased completely when you delete your account.

When you sign out, the app removes your account's highlights, notes and Memorize list from that phone, so the next person to sign in on it doesn't see them. They stay in your account and come back when you sign in again. If some haven't been uploaded yet, the app tells you before removing them.

## The Bible Helper (AI answers)

The Bible Helper is an optional feature, for signed-in accounts, that answers questions about the Bible with AI. It's in the app's **Ask** tab. If you don't use it, or your version of the app has no **Ask** tab, nothing in this section applies to you.

When you ask a question:

- **The app sends** your question, up to the last few messages of that conversation (so the answer can follow it), and the app's language to our server. If you asked from the reader with **Ask about this verse**, it also sends the verses you selected: their reference and their text in your Bible translation. For the licensed translations read through YouVersion (see below), their licenses don't allow it, so the app sends only the reference and the translation's name, and our server gives the AI the same verses from the public-domain Berean Standard Bible instead.
- **Our server adds** its own fixed instructions and sends the conversation to [Cloudflare Workers AI](https://developers.cloudflare.com/workers-ai/platform/data-usage/), which runs the AI model and sends the answer back. It may first send your latest question to Cloudflare on its own, to turn it into numbers that describe its meaning, as search by meaning does; the server uses them to pick which of our own notes on common topics to add to the instructions. Cloudflare acts as our service provider: it doesn't use what we send to train AI models and doesn't keep it after answering, except where the law requires it. Your account, email address and IP address aren't sent to Cloudflare.
- **Our server doesn't store** your questions or the answers, and never writes them to its logs. It keeps only a count of how many questions your account asked each day, to apply the daily limit; the count is deleted with your account.
- **Your conversations are saved on your phone**, so you can read them again. They aren't in your phone's cloud backup; a direct phone-to-phone transfer can copy them along with your downloaded Bibles. You can delete them in the app, and they're removed when you sign out or remove the app.

If you **report an answer**, the app sends us the question, the answer, the reason you chose, an optional comment and the app's language. We store the report **without your account or any other way to tell who sent it**, read it to improve the Bible Helper's instructions, and delete it after 90 days.

Answers are written by AI and can be wrong; check them against Scripture. Please don't include personal details, such as names, addresses or health information, in your questions.

## Search by meaning

The **Search** tab finds verses in two ways. Verses that contain the words you typed are found on your phone, and that search works without internet; in one of the licensed translations, YouVersion finds them (see "Content the app downloads" below). When you press the search button (or Enter), the app also looks for verses **related by meaning**, which needs our server:

- **The app sends** what you typed (up to 200 characters) to our server, and nothing else: not your account, even if you're signed in. Nothing is sent while you're still typing, or when you search for a verse reference (like "John 3:16") or a phrase in quotation marks.
- **Our server sends** that text to [Cloudflare Workers AI](https://developers.cloudflare.com/workers-ai/platform/data-usage/), which turns it into numbers that describe its meaning and sends them back. The server compares them with the Bible's verses and sends the app a list of verse references, which the app shows in your own Bible translation. Cloudflare acts as our service provider: it doesn't use what we send to train AI models and doesn't keep it, except where the law requires it. Your IP address isn't sent to Cloudflare.
- **Our server doesn't store** your searches and never writes them to its logs. To stay within its free limits it counts searches (how many today, and how many from each IP address in the last minute), never what was searched; the counts are kept only in memory.
- **If the results didn't help**, you can tap **These results didn't help** under them. The app first shows everything it would send, and sends it only if you tap **Send**: what you searched, the app's language and version, the Bible translation the results were shown in, and the references of the verses it showed (not their text). We store the report **without your account, your IP address or any other way to tell who sent it**, read it to make search better (for example, by adding it to the searches we test with), and delete it after 90 days. To stop too many reports from one place, the server counts how many came from each IP address in the last hour, in memory only.

## Deleting your account

You can delete your account at any time:

- **In the app:** open **Settings** (the gear on the You tab), and under **Account** tap **Delete account**.
- **By email:** if you no longer have the app, email [pinoponedaniel@gmail.com](mailto:pinoponedaniel@gmail.com) from your account's email address and ask us to delete it. We'll delete it within 30 days.

Deleting your account permanently erases it from our server, together with everything synced to it: highlights, notes and verses you're memorizing (including ones marked as deleted), reading position, sign-in tokens, emailed codes, the Bible Helper's daily question count and the Google account ID if you used Google sign-in. If you signed up with Google, the app asks you to confirm with Google instead of a password. Copies can remain in our database provider's backups for a short time until they're replaced. Highlights, notes and verses you're memorizing already on your phone stay there until you remove the app.

## How long we keep information

We keep your account information until you delete your account. Technical logs are kept by our hosting provider for the limited time its own policies allow.

## Content the app downloads

To show Bible text, the verse of the day and your Google profile picture, and to play the chapter audio you listen to, the app downloads content from:

- **[bible.helloao.org](https://bible.helloao.org)**: Bible translations you choose to download, and audio you choose to play (streamed from audio.bible.helloao.org while you listen, not saved on your phone).
- **[OurManna](https://ourmanna.com)**: the daily verse.
- **The [YouVersion Platform](https://developers.youversion.com)** (api.youversion.com), run by YouVersion, Inc.: the licensed translations listed under **Licensed versions** in the version picker, such as LBLA, NBLA, NASB and AMP. When you choose one, the app gets its list of books; after that it gets each chapter the first time you open it, or the first time a Bible Helper answer or a search by meaning shows a verse from it, and keeps it on your phone, so it opens offline next time. When you search one of these translations for words, the words you typed (up to 100 characters) are sent to YouVersion, which finds the verses in the whole translation; this happens as you type, once you pause, and not when you search for a verse reference (like "John 3:16") or have no internet, when the app searches only the chapters already on your phone. Each request tells YouVersion which translation and chapter, or which words, and carries the app's own key, which identifies TreasuredWord, not you. YouVersion may give each translation's publisher statistics on how often it's read, without information about who reads it. YouVersion's handling of these requests is covered by [its privacy policy](https://www.bible.com/privacy).
- **Google's image server**: your Google account's profile picture, only if you sign in with Google (see "Signing in with Google" above).

These requests carry no TreasuredWord account information. Like any web request, they let those services see your device's internet (IP) address.

## Outside links and videos

TreasuredWord links to resources made by other ministries. They open outside the app:

- **GotQuestions.org** answers and book summaries open in your phone's browser (shown as a browser sheet over the app). GotQuestions.org and your browser's provider see that visit.
- **BibleProject** overview videos open in the YouTube app, or on youtube.com if the app isn't installed. Google (YouTube) sees what you watch.
- **Video previews on Home.** Home shows the picture (thumbnail) of the BibleProject videos for the book you're reading. The app loads these pictures from YouTube's image server (`i.ytimg.com`) when Home opens, so Google sees your device's internet (IP) address and which video pictures were loaded, even if you don't play a video. Nothing else is sent. When you're offline, or Android's Data Saver is on, the app loads no pictures and shows a colored tile instead.

The app sends these services no account information and doesn't keep a record of what you open. Your visit is covered by their own privacy policies: [GotQuestions.org](https://www.gotquestions.org/privacy.html), [Google](https://policies.google.com/privacy) and your browser's.

## Backups and new phones

If Android backup is turned on, Android copies your TreasuredWord settings (reading position, bookmark, theme, text size, reminder and language) to your Google account backup. When you move to a new phone with a cable or direct transfer, Android also copies the highlights, notes, Memorize list and translations stored on the phone. Your sign-in is never copied; you sign in again on the new phone.

## Notifications

The daily verse reminder is scheduled on your phone. Turning it on or off doesn't send anything to us.

## Security

The app talks to our server only over encrypted connections (HTTPS). Passwords are stored only as hashes, and sign-in tokens on your phone are encrypted. No system is perfectly secure, but we work to protect your information.

## Your rights

You can ask us for a copy of the information your account holds, to correct it, or to delete it, by emailing [pinoponedaniel@gmail.com](mailto:pinoponedaniel@gmail.com). You can also delete everything yourself in the app. Depending on where you live (for example the European Union, the United Kingdom or California), you may have further rights under local law, including the right to complain to your data protection authority.

## Children

TreasuredWord isn't directed at children under 13, and we don't knowingly collect personal information from them. A child can use the app without an account, in which case nothing they create leaves their phone. If you believe a child under 13 has created an account, email us and we'll delete it.

## Changes to this policy

If this policy changes, we'll update it here and change the effective date above.

## Contact

Questions about privacy: [pinoponedaniel@gmail.com](mailto:pinoponedaniel@gmail.com)
