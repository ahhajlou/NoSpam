// SPDX-License-Identifier: GPL-3.0-or-later

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val appVersionName = "0.2.0"

// Release signing material never lives in this repository. It is read from
// keystore.properties at the root (gitignored, see keystore.properties.template)
// or, in CI, from the matching environment variables. When neither is present
// the release build type simply has no signing config and `assembleRelease`
// produces an unsigned APK, so a clone without the key still builds.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingSetting(propertyKey: String, envName: String): String? =
    (keystoreProperties.getProperty(propertyKey) ?: System.getenv(envName))
        ?.takeIf { it.isNotBlank() }

val keystorePath = signingSetting("storeFile", "ANDROID_KEYSTORE_FILE")
val keystoreFile = keystorePath?.let { path ->
    File(path).takeIf { it.isAbsolute } ?: rootProject.file(path)
}
val hasReleaseSigning = keystoreFile?.exists() == true

base {
    archivesName = "NoSpam-$appVersionName"
}

tasks.withType<Test> {
    // AppContainer wiring touches PhoneNumberNormalizer's cache and the
    // process-wide DataStore delegates in core:preferences, both singletons for
    // the life of the process (see core:telephony's build.gradle.kts). One JVM
    // fork per test class keeps that state from leaking between test classes.
    forkEvery = 1
}

android {
    namespace = "com.nospam.nospam"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.nospam.nospam"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    androidResources {
        localeFilters += listOf("en", "fa")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = keystoreFile
                storePassword = signingSetting("storePassword", "ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signingSetting("keyAlias", "ANDROID_KEY_ALIAS")
                keyPassword = signingSetting("keyPassword", "ANDROID_KEY_PASSWORD")
                    ?: signingSetting("storePassword", "ANDROID_KEYSTORE_PASSWORD")
                // v1 (jar) signing is what pre-API-24 devices need; minSdk is 26,
                // so only the APK Signature Scheme v2/v3 blocks are required.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    // ABI splits disabled: the app has negligible native code, so split APKs
    // end up nearly the same size as the universal APK — splitting just adds
    // download/manifest complexity with no size benefit.
    // splits {
    //     abi {
    //         isEnable = true
    //         reset()
    //         include("armeabi-v7a", "arm64-v8a")
    //         isUniversalApk = true
    //     }
    // }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }

    lint {
        checkReleaseBuilds = true
        // The gate is only a gate if it can fail. This was false, so CI's
        // `./gradlew build` could not fail on any lint issue, including the
        // MissingPermission Error in BackfillProgressNotifier.
        abortOnError = true
        error += setOf("UnsafeIntentLaunch", "MutableImplicitPendingIntent")
    }
}

dependencies {
    baselineProfile(project(":baselineprofile"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:i18n"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:telephony"))
    implementation(project(":core:ml"))
    implementation(project(":core:notifications"))
    implementation(project(":core:preferences"))
    implementation(project(":feature:conversations"))
    implementation(project(":feature:thread"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:onboarding"))
    debugImplementation(project(":feature:export"))
    debugImplementation(project(":feature:mldebug"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
