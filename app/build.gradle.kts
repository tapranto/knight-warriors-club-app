plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.knightwarriorsclub.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.knightwarriorsclub.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 55
        versionName = "0.6.1-beta50"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Release signing is configured via keystore.properties (NEVER committed).
            // See README.md — "Signing key" section.
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Phase 2: Supabase REST/Auth over HTTPS (same database as the website)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Phase 3: WebRTC media for 1-to-1 calls (signalling rides RealtimeClient)
    implementation("io.github.webrtc-sdk:android:125.6422.07")

    // Firebase Cloud Messaging — the call "doorbell" (Phase 3.1).
    // BoM pins compatible versions; messaging is the only product used.
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-messaging")
}
