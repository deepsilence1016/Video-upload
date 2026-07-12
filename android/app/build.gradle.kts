// ============================================================
// app/build.gradle.kts — Production Grade Vision Agent
// ============================================================
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    jacoco
}

android {
    namespace = "com.visionagent"
    compileSdk = 35
    ndkVersion = "27.0.12077973"  // Must match CI NDK_VERSION

    defaultConfig {
        applicationId = "com.visionagent.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // NDK ABI Filters — Only arm64-v8a & x86_64 for performance
        ndk {
            // arm64-v8a only: Tesseract/OpenCV prebuilts are arm64 only
            // x86_64 re-enable when emulator libs are available
            abiFilters += listOf("arm64-v8a")
        }

        // CMake — Native Vision & OCR Engine
        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++17",
                    "-O3",
                    "-DNDEBUG",
                    "-ffast-math",
                )
                // ABI-specific flags handled in CMakeLists.txt per ANDROID_ABI
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=ON",     // SIMD acceleration
                    "-DCMAKE_BUILD_TYPE=Release"
                )
            }
        }

        // Room schema export for migration tracking
        kapt {
            arguments {
                arg("room.schemaLocation", "$projectDir/schemas")
                arg("room.incremental", "true")
            }
        }

        // FIX BUILDCONFIG-1: buildConfigField uses Java type names, not Kotlin.
        // "Int" → Java has no `Int` class → "cannot find symbol class Int"
        // Correct Java types: "int", "long", "boolean", "String", "float"
        buildConfigField("String", "AGENT_VERSION", "\"1.0.0-alpha\"")
        buildConfigField("boolean", "ENABLE_VERBOSE_LOGGING", "false")
        buildConfigField("int", "MAX_FRAME_QUEUE_SIZE", "10")
        buildConfigField("long", "SESSION_TIMEOUT_MS", "30000L")
    }

    // ── Signing Config ────────────────────────────────────────────────────────
    // FIX SIGN-1: signingConfigs MUST be declared BEFORE buildTypes in Kotlin DSL.
    // In Kotlin DSL, buildTypes.release { signingConfig = signingConfigs.getByName("release") }
    // is evaluated eagerly at configuration time. If signingConfigs block appears AFTER
    // buildTypes, the "release" signingConfig does not yet exist → "not found" error.
    // Groovy DSL defers evaluation (lazy); Kotlin DSL does not — order matters.
    //
    // FIX SIGN-2: All four keystore fields must be non-null for AGP to accept the config.
    // If any env var is missing (local dev build, PR build without secrets), AGP throws.
    // Solution: only assign signingConfig in release buildType when ALL secrets are present.
    signingConfigs {
        create("release") {
            // Values come from GitHub Secrets injected as environment variables in CI.
            // Local builds: set these in ~/.gradle/gradle.properties or leave unset (debug APK).
            // FIX KEYSTORE-2: Guard against empty string KEYSTORE_PATH.
            // When CI skips signing (no secret set), KEYSTORE_PATH is set to ""
            // (empty string, not null). file("") is a valid File object but
            // storeFile = file("") → AGP tries to open "" → FileNotFoundException.
            // Guard: check both non-null AND non-blank before assigning storeFile.
            val keystorePath  = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
            val keystorePass  = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
            val keyAliasName  = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
            val keyPass       = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }

            if (keystorePath != null && keystorePass != null &&
                keyAliasName != null && keyPass != null) {
                storeFile     = file(keystorePath)
                storePassword = keystorePass
                keyAlias      = keyAliasName
                keyPassword   = keyPass
            }
            // If any env var is null/blank, signingConfig exists but storeFile is null.
            // buildTypes.release checks storeFile != null before assigning signingConfig.
        }
    }

    // ── Build Types ───────────────────────────────────────────────────────────
    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "ENABLE_VERBOSE_LOGGING", "true")
            buildConfigField("boolean", "ENABLE_PERF_TRACING", "true")
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // FIX SIGN-3: Only assign signingConfig when keystore secrets are available.
            // signingConfigs["release"] always exists (created above), but storeFile is
            // null when env vars are absent. Guard prevents unsigned-config assignment.
            val releaseConfig = signingConfigs.getByName("release")
            if (releaseConfig.storeFile != null) {
                signingConfig = releaseConfig
            }
            // Without signingConfig, AGP produces an unsigned release APK — acceptable
            // for PR/branch builds. Tag builds (with secrets) produce signed APKs.
        }
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    // ── Compiler Options ──────────────────────────────────────────────────────
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // FIX KAPT-WARN: kapt does not support Kotlin language version 2.0+.
        // Explicitly set language version to 1.9 for kapt annotation processing only.
        // The rest of the project compiles with Kotlin 2.0 features.
        languageVersion = "1.9"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlin.RequiresOptIn"
            // FIX WARN-1: -Xsuppress-warning=DEPRECATION is not a valid Kotlin compiler
            // flag — removed. BuildConfig deprecation warning is informational only
            // and does not affect compilation or functionality.
        )
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // FIX CMAKE-2: Do NOT pin cmake version here.
            // version = "3.22.1" tells AGP to look for cmake ONLY in
            // $ANDROID_HOME/cmake/3.22.1/ — if sdkmanager installs it there
            // but the binary is broken/missing, AGP throws:
            // "Could not get version from cmake.dir path '.../cmake/3.22.1'"
            //
            // Without a version pin, AGP uses the highest cmake available:
            //   1. Any version in $ANDROID_HOME/cmake/*/
            //   2. System cmake on PATH (GitHub runner = 3.25.x — fully compatible)
            // AGP 8.x + NDK r27 requires cmake >= 3.18 — any modern cmake works.
            // Removing the pin is the correct production approach.
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Kotlin
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    // Android Core
    implementation(libs.core.ktx)
    implementation(libs.appcompat)

    // Hilt DI
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.work)
    kapt(libs.hilt.work.compiler)

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.prefs)
    implementation(libs.datastore.core)

    // WorkManager
    implementation(libs.workmanager.ktx)

    // Lifecycle
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.viewmodel)

    // Security
    implementation(libs.security.crypto)
    implementation(libs.biometric)

    // Networking
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)

    // Performance
    implementation(libs.startup)
    implementation(libs.tracing)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(libs.mockk.android)
}

// Kapt config for Hilt
kapt {
    correctErrorTypes = true
    useBuildCache = true
}

// ── Detekt — Static Analysis ─────────────────────────────────────────────
detekt {
    // rootProject.projectDir = android/ (where settings.gradle.kts lives)
    // quality/ is one level above android/ at the repo root
    config.setFrom("${rootProject.projectDir}/../quality/detekt/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
    autoCorrect = false
    parallel = true
    source.setFrom(
        "src/main/kotlin",
        "src/test/kotlin",
        "src/androidTest/kotlin",
    )
}

dependencies {
    detektPlugins(libs.detekt.formatting)
}

// ── KtLint — Code Style ───────────────────────────────────────────────────
configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    version.set("1.3.1")
    android.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.HTML)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}

// ── JaCoCo — Code Coverage ────────────────────────────────────────────────
jacoco {
    toolVersion = "0.8.12"
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    val fileFilter =
        listOf(
            "**/R.class",
            "**/R$*.class",
            "**/BuildConfig.*",
            "**/Manifest*.*",
            "**/*Test*.*",
            "android/**/*.*",
            "**/*_MembersInjector.class",
            "**/Dagger*Component.class",
            "**/Dagger*Component$*Builder.class",
            "**/*Module_*Factory.class",
            "**/Hilt_*.class",
            "**/*_HiltComponents.class",
        )

    val debugTree =
        fileTree("${layout.buildDirectory.get()}/intermediates/javac/debug/classes") {
            exclude(fileFilter)
        }
    val kotlinDebugTree =
        fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
            exclude(fileFilter)
        }

    sourceDirectories.setFrom(files("src/main/kotlin", "src/main/java"))
    classDirectories.setFrom(files(debugTree, kotlinDebugTree))
    executionData.setFrom(
        fileTree(layout.buildDirectory.get()) {
            include(
                "jacoco/testDebugUnitTest.exec",
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
            )
        },
    )
}
