# Releasing TreasuredWord to Google Play

How to build a signed Android App Bundle (AAB) for Google Play. The repo is public, so **the
upload key and its passwords never go in the repo**: not in code, issues, PRs or commit messages.

## 1. Create the upload key (once)

Google Play uses two keys:

- The **upload key** is yours. You sign each bundle with it before uploading.
- The **app signing key** is Google's (Play App Signing, step 3). Google re-signs the app with it
  before it reaches phones.

If the upload key is ever lost or leaked, Google can replace it, because they hold the app
signing key. That's why Play App Signing is worth opting in to.

Create the key with `keytool` (it comes with Android Studio's JDK, in
`C:\Program Files\Android\Android Studio\jbr\bin`). Keep it **outside the repo**, for example in
`C:\Users\<you>\keys\`. `keytool` doesn't create folders, so make the folder first. In PowerShell:

```powershell
New-Item -ItemType Directory -Force ~/keys
keytool -genkeypair -v -keystore ~/keys/treasuredword-upload.jks -storetype PKCS12 -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

`keytool` asks for a password and your name/organization. The last question wants a two-letter
**country** code (`US`), not a state. Pick a strong password and save it in a password manager. A PKCS12 keystore uses the same password for the store and the key, so use it
for both `storePassword` and `keyPassword` below. `-validity 10000` (about 27 years) is what
Google recommends; Play requires the key to be valid until at least 2033.

### Back it up

Losing the upload key means asking Google support for a reset, which takes days. Keep:

- the `.jks` file in at least two places outside this computer (an encrypted USB drive and a
  private cloud folder, for example), and
- the password in your password manager, **not** next to the file.

Never email it or attach it to a GitHub issue.

## 2. Tell Gradle where the key is

Copy `keystore.properties.example` (repo root) to `keystore.properties` and fill it in.
`keystore.properties` is git-ignored, and so are `*.jks` and `*.keystore` files. Afterwards, run
`git status`: if `keystore.properties` shows up, the branch you're on is older than the ignore
rule, so delete the file and switch to a branch made from the latest `main`.

```properties
storeFile=C:/Users/<you>/keys/treasuredword-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Use forward slashes in the path, even on Windows. A relative path is taken from the repo root.

For CI, set environment variables instead (the file wins if both exist):
`TREASUREDWORD_STORE_FILE`, `TREASUREDWORD_STORE_PASSWORD`, `TREASUREDWORD_KEY_ALIAS`,
`TREASUREDWORD_KEY_PASSWORD`.

If neither is set, release builds come out **unsigned** (Play rejects them) and debug builds work
as usual. If only some of the values are set, the build stops and names what's missing.

## 3. Opt in to Play App Signing (once)

New apps that upload App Bundles must use Play App Signing, so this is mostly a matter of
accepting the default. In Play Console: **TreasuredWord → Test and release → App integrity → Play
app signing**. If it asks, choose **Use a Google-generated key** (the recommended choice). Your
first upload then registers the key it was signed with as your upload key. The app signing key
stays with Google; nothing about it goes in this repo.

## 4. Bump the version (every release)

In `app/build.gradle.kts`, `defaultConfig`:

- `versionCode`: a whole number that must go **up by at least 1** for every bundle you upload,
  including internal-testing ones. Play rejects a number it has seen before.
- `versionName`: what users see, e.g. `"1.0"`, `"1.0.1"`, `"1.1"`.

## 5. Build the bundle

```bash
./gradlew :app:bundleRelease
```

The bundle is `app/build/outputs/bundle/release/app-release.aab`. Check it's signed with your
upload key (it should print `jar verified.`):

```bash
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
```

Upload it in Play Console (**Test and release → Testing → Internal testing** is a good first
track).

## Code shrinking (R8)

Release builds run R8 (`isMinifyEnabled` and `isShrinkResources`), which removes unused code and
resources and shortens class names. The release APK is about 4.6 MB. The catch: code that is looked
up by name while the app runs can break, and that only shows up in a release build, never in
debug.

The keep rules are in `app/proguard-rules.pro`. Room, kotlinx.serialization, WorkManager, Ktor and
Tink ship their own rules, so the file only holds what they miss.

R8 also writes a mapping file (old names → new names). It goes inside the App Bundle, and Play
Console uses it to show readable crash stack traces. Nothing to upload by hand.

### Check the release build before uploading

Install it on the **emulator**, not your own phone. A release build is signed with a different
key than the debug build already on a phone, so Android won't install one over the other. You'd
have to uninstall the debug app first, which deletes its local data.

```bash
./gradlew :app:installRelease
```

Then: open a chapter and go to the next one, download a translation and read it, search, open
"About this book", change the theme, turn on the daily verse reminder, **sign in and check that
highlights and notes sync**, and restart the app.

The first R8 build (October 2026) passed all of these on an API 32 emulator except sign-in and
sync, which need a real account and weren't tried. Check those yourself before the first upload.

Once the app is on Play's internal testing track, the same applies to your phone: the Play
version is signed with Google's key, so the debug build has to be uninstalled before the Play one
installs. Signing back in brings the synced data back.
