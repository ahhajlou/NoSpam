plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.nospam.nospam.baselineprofile"
    compileSdk = 36

    defaultConfig {
        // Capturing a profile on an unrooted device needs API 33+, so generation
        // runs on the API 36 emulator, not the SM-A730F (Android 9) the perf
        // gates are measured on. The profile it emits is still consumed by the
        // minSdk 26 release build through ProfileInstaller.
        minSdk = 28
        targetSdk = 36
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

    targetProjectPath = ":app"
}

baselineProfile {
    // A profile generator, not a benchmark suite: one connected device is enough.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.junit)
}
