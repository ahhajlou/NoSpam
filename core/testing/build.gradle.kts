// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.library)
}

// Test-support module. It is the one deliberate exception to CLAUDE.md §3.4's
// "core:* modules never depend on each other": a fake is only useful if it
// implements the real interface, so this module must see the modules whose
// ports it fakes. It is never on a production classpath — only `testImplementation`
// and `androidTestImplementation` depend on it. It is an `android.library`
// rather than `kotlin("jvm")` for the same reason: `SpamClassifier` and
// `TelephonyDataSource` live in Android library modules, and a pure-JVM module
// cannot implement them. That mismatch is why every consuming test used to
// hand-roll its own copy of these fakes.
android {
    namespace = "com.nospam.nospam.core.testing"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    // The interfaces these fakes implement.
    api(project(":core:ml"))
    api(project(":core:telephony"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
