# R8 rules for release builds (see docs/release.md, "Code shrinking").
#
# Room, kotlinx.serialization, WorkManager, Ktor, OkHttp and Tink ship their own rules inside their
# libraries; this file only covers what they miss. After adding a dependency, build with
# ./gradlew :app:assembleRelease and check the app on the emulator: R8 lists anything else it
# can't find in app/build/outputs/mapping/release/missing_rules.txt.

# Keep file names and line numbers so Play Console's crash reports point at real lines. The
# mapping file inside the App Bundle turns the shortened class names back into real ones.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Compile-time annotations that Tink (used by androidx.security-crypto for TokenStore) refers to
# but doesn't ship. Nothing reads them while the app runs.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# Credential Manager finds its Google Play services provider by reflection (#112), as its docs say.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
  *;
}

# Ktor logs through slf4j, which looks for an optional logger binder. Without one it falls back to
# a no-op logger, which is what we want on Android.
-dontwarn org.slf4j.impl.StaticLoggerBinder
