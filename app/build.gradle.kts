plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val appVersionName = "0.1.0-alpha.4"

base {
    archivesName = "NoSpam-$appVersionName"
}

android {
    namespace = "com.nospam.nospam"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.nospam.nospam"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    androidResources {
        localeFilters += listOf("en", "fa")
    }

    buildTypes {
        release {
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
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
