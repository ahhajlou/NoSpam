plugins {
    alias(libs.plugins.android.library)
}

tasks.withType<Test> {
    // PhoneNumberNormalizer caches its resolved country ISO in a `@Volatile`
    // object-level field for the life of the process (by design -- see its
    // KDoc). Robolectric can reuse one JVM/classloader across test classes, so
    // one JVM fork per test class keeps each class's first resolution
    // independent of any other class's Locale/SIM setup (same fix as
    // feature:settings' SpamPreferences DataStore singleton).
    forkEvery = 1
}

android {
    namespace = "com.nospam.nospam.core.telephony"
    compileSdk = 36

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
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(project(":core:notifications"))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
