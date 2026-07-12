// ============================================================
// ROOT build.gradle.kts — Production Grade Vision Agent
// ============================================================
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}

// Global configuration
ext {
    set("compileSdk", 35)
    set("minSdk", 26)          // Android 8.0+ — required for advanced APIs
    set("targetSdk", 35)
    set("versionCode", 1)
    set("versionName", "1.0.0-alpha")
}

// Apply ktlint to all subprojects so "ktlintCheck" is available from root
// This ensures ./gradlew ktlintCheck and ./gradlew app:ktlintCheck both work
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}
