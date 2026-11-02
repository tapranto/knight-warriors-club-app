// Root build file — plugin versions for the whole project.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
    // Firebase (Phase 3.1, 2026-11-02) — version recommended by the console
    id("com.google.gms.google-services") version "4.5.0" apply false
}
