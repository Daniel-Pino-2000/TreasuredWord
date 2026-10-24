import java.util.Properties
import org.gradle.kotlin.dsl.implementation

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-kapt")

    alias(libs.plugins.kotlin.plugin.serialization)
}

// Release signing for Google Play (see docs/release.md). The upload key and its passwords never
// enter the repo: they come from a git-ignored keystore.properties at the repo root, or from
// TREASUREDWORD_* environment variables (for CI). With neither, release builds come out unsigned
// and debug builds are unaffected, so CI and fresh clones keep working.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val releaseSigning: Map<String, String>? = run {
    val keys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    val values = if (keystorePropertiesFile.exists()) {
        val props = Properties().apply { keystorePropertiesFile.inputStream().use { load(it) } }
        keys.associateWith { props.getProperty(it)?.trim().orEmpty() }
    } else {
        // storeFile -> TREASUREDWORD_STORE_FILE, keyAlias -> TREASUREDWORD_KEY_ALIAS, ...
        keys.associateWith { key ->
            val env = "TREASUREDWORD_" + key.replace(Regex("([A-Z])"), "_$1").uppercase()
            providers.environmentVariable(env).orNull?.trim().orEmpty()
        }
    }
    when {
        values.values.all { it.isEmpty() } -> null
        // Half-filled settings are a mistake, not "no signing": fail instead of silently
        // building an unsigned bundle.
        values.values.any { it.isEmpty() } -> error(
            "Release signing is missing " + values.filterValues { it.isEmpty() }.keys.joinToString() +
                " (in keystore.properties or TREASUREDWORD_* variables). See docs/release.md."
        )
        else -> values
    }
}

android {
    namespace = "com.application.bibleapp"
    compileSdk = 36

    defaultConfig {
        // TreasuredWord's permanent Google Play ID, reserved in the Play Console. The code keeps the
        // old com.application.bibleapp namespace; only the installed app's ID changed.
        applicationId = "com.treasuredword.app"
        minSdk = 24
        // Google Play only accepts new apps and updates that target API 36 or higher (#66).
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                // A relative path is taken from the repo root; keep the key itself outside the repo.
                storeFile = rootProject.file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // en-XA (accented, longer text) and ar-XB (mirrored, right-to-left) show up in the
            // device's language list, to catch hard-coded strings and labels that don't fit.
            isPseudoLocalesEnabled = true
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            // R8 shrinks and obfuscates the code; the mapping file goes inside the App Bundle, so
            // Play Console de-obfuscates crash reports by itself. Keep rules: proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // Lists the languages the app has resources for (plus res/resources.properties' default)
        // in a generated locales_config.xml, so Android 13+ offers them in the system's
        // per-app language settings.
        generateLocaleConfig = true
    }
    bundle {
        // Play otherwise installs only the device's languages from an App Bundle, so a language
        // picked in the app's own settings could be missing. The strings are tiny.
        language {
            enableSplit = false
        }
    }
}

dependencies {

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.8")

    // Ktor
    implementation("io.ktor:ktor-client-core:2.3.8")
    // OkHttp, not CIO: CIO's TLS handshake doesn't implement the hostname-aware
    // X509ExtendedTrustManager API that Android requires once the network security config
    // has any <domain-config> block (ours does, for the debug-only cleartext exception) -
    // without this, HTTPS to any other host (bible.helloao.org, etc.) throws
    // "Domain specific configurations require that hostname aware checkServerTrusted(...)
    // is used". OkHttp delegates through Android's trust manager correctly.
    implementation("io.ktor:ktor-client-okhttp:2.3.8")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.8")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.8")
    // Automatically attaches "Authorization: Bearer <token>" to backend requests and
    // transparently retries with a refreshed token on a 401 - see HttpClientProvider.
    implementation("io.ktor:ktor-client-auth:2.3.8")

    // Encrypted on-disk storage for the access/refresh tokens - see data/local/TokenStore.kt
    implementation("androidx.security:security-crypto:1.1.0")


    // Icons
    implementation("androidx.compose.material:material-icons-extended:1.7.8") // Replace 1.x.x with the latest version

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.4")
    implementation(libs.androidx.benchmark.traceprocessor.android)

    // Background scheduling for the daily verse fetch + notification
    implementation(libs.androidx.work.runtime.ktx)

    // Chrome Custom Tabs for outside web pages (GotQuestions.org) - see utils/ExternalLinks.kt
    implementation(libs.androidx.browser)

    // Room
    val room_version = "2.7.1"
    implementation("androidx.room:room-ktx:$room_version")
    implementation("androidx.room:room-runtime:2.7.1")
    kapt("androidx.room:room-compiler:$room_version")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    // Per-app language below Android 13 (AppCompatDelegate.setApplicationLocales) - see utils/AppLanguage.kt
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    // runTest/virtual time for VerseSearchTest - matches the kotlinx-coroutines version the app already resolves
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // MockEngine for BackendBearerTest (#86) - same version as the app's other Ktor client artifacts
    testImplementation("io.ktor:ktor-client-mock:2.3.8")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}