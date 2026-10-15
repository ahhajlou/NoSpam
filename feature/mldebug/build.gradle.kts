// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nospam.nospam.feature.mldebug"
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
    buildFeatures {
        compose = true
    }
}

// The view model traces messages through NativeCore, so the JVM tests load the host build
// of the Rust core, which core:ml makes and shares as its `nativeCoreHost` variant.
val nativeCoreHost = configurations.dependencyScope("nativeCoreHost")
val nativeCoreHostFiles = configurations.resolvable("nativeCoreHostFiles") {
    extendsFrom(nativeCoreHost.get())
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("nospam-native-core-host")) }
}

tasks.withType<Test>().configureEach {
    val hostDir = nativeCoreHostFiles.get()
    inputs.files(hostDir).withPropertyName("nativeCoreHost").withPathSensitivity(PathSensitivity.RELATIVE)
    jvmArgumentProviders.add { listOf("-Djava.library.path=${hostDir.singleFile.path}") }
    jvmArgs("--enable-native-access=ALL-UNNAMED") // JEP 472, as in core:ml
}

dependencies {
    nativeCoreHost(project(":core:ml"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:ml"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}