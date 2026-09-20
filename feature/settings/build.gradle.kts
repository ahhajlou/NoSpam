// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

tasks.withType<Test> {
    // SpamPreferences' `Context.spamDataStore` is a classloader-wide DataStore
    // singleton (one `by preferencesDataStore(...)` delegate for the whole
    // file): once any test resolves it, every other Context reuses the same
    // underlying file for the rest of that JVM's life. Robolectric reuses one
    // JVM/classloader across test classes by default, so SpamPreferencesTest's
    // working DataStore was leaking into SpamPreferencesFailureTest and
    // silently defeating its forced-failure Contexts. One JVM fork per test
    // class keeps each class's first DataStore access genuinely first.
    forkEvery = 1
}

android {
    namespace = "com.nospam.nospam.feature.settings"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
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
    testOptions {
        unitTests {
            // Compose UI tests run on the JVM through Robolectric; they need the
            // merged resources and the Compose test manifest's activity.
            isIncludeAndroidResources = true
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:telephony"))
    implementation(project(":core:common"))
    implementation(project(":core:i18n"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.androidx.test.core)
}