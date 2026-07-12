// ============================================================
// settings.gradle.kts — Root Project Settings
// Vision Agent Android
//
// FIX GRADLE-1: This file was MISSING — without settings.gradle.kts,
// Gradle cannot locate the project root, resolve plugin IDs, or find
// the pluginManagement/dependencyResolutionManagement repositories.
// Result: "Plugin [id: 'com.android.application', version: '8.5.0']
// was not found" — because google() repo was never declared.
// ============================================================

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // For security-crypto alpha and biometric alpha
        maven { url = uri("https://androidx.dev/storage/compose-compiler/repository/") }
    }
}

rootProject.name = "VisionAgent"
include(":app")
