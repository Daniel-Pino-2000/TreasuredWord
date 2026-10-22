import org.gradle.kotlin.dsl.implementation

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-kapt")

    alias(libs.plugins.kotlin.plugin.serialization)
}

android {
    namespace = "com.application.bibleapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.application.bibleapp"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // en-XA (accented, longer text) and ar-XB (mirrored, right-to-left) show up in the
            // device's language list, to catch hard-coded strings and labels that don't fit.
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = false
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
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}