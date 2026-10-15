// SPDX-License-Identifier: GPL-3.0-or-later

import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.nospam.nospam.core.ml"
    compileSdk = 37
    // The NDK cargo-ndk links the Rust core with: AGP 9.4's default. r28 and later
    // align native libraries for 16 KB pages. CI installs the version named here.
    ndkVersion = "28.2.13676358"

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

    sourceSets {
        // The parity check runs on the JVM against the host build of the core and on
        // a device against the phone's build: one copy of it, and the fixture is also
        // an asset of the test APK.
        getByName("test").kotlin.directories += "src/sharedTest/kotlin"
        getByName("androidTest") {
            kotlin.directories += "src/sharedTest/kotlin"
            assets.directories += "src/test/resources"
        }
    }
}

/*
 * The Rust core: persian-sms-spam's core/ (the submodule), compiled here from source,
 * twice, from the same sources with the same profile:
 * - cargoNdkBuild: libspam_core_jni.so for each ABI, packaged as this module's jniLibs;
 * - cargoHostBuild: the same library for this machine, loaded by the JVM unit tests
 *   (here and in feature:mldebug), so they check the code the phone runs.
 * Release builds for every variant: the tests then check what ships, and debug Rust is
 * far slower, against a classify budget of 3 s.
 */
val rustWorkspace = rootProject.layout.projectDirectory.dir("third_party/persian-sms-spam/core")
val rustDir = layout.buildDirectory.dir("rust")
val rustSources = rustWorkspace.asFileTree.matching {
    include("Cargo.toml", "Cargo.lock", "rust-toolchain.toml", "*/Cargo.toml")
    include("spam-core/src/**", "spam-core/rules/**", "spam-core-jni/src/**")
}

// Measured 2026-10-15: LTO makes the three libraries 13% smaller (6.5 MB against 7.5 MB
// stripped) at the same speed. Set here rather than in the core's Cargo.toml, so the
// submodule stays at the commit the model was exported from. Never `panic = "abort"`:
// the JNI layer turns a panic into a Java exception with catch_unwind.
val cargoProfile = mapOf(
    "CARGO_PROFILE_RELEASE_LTO" to "true",
    "CARGO_PROFILE_RELEASE_CODEGEN_UNITS" to "1",
)

// rustup's cargo, which reads the core's rust-toolchain.toml, even when the Gradle daemon
// was started without ~/.cargo/bin on its PATH (the IDE shares the daemon).
val cargoExecutable = providers.environmentVariable("CARGO_HOME")
    .orElse(providers.systemProperty("user.home").map { "$it/.cargo" })
    .map { home -> File(home, "bin/cargo").takeIf { it.canExecute() }?.path ?: "cargo" }

/** Runs cargo in the core's workspace; up to date while its sources and settings are unchanged. */
@DisableCachingByDefault(because = "cargo keeps its own incremental build in targetDir")
abstract class CargoBuild @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
    /** Manifests, lock file, toolchain file, Rust sources and the rules compiled into them. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** cargo's arguments; `{out}` stands for [outputDir]. */
    @get:Input
    abstract val arguments: ListProperty<String>

    @get:Input
    abstract val cargoEnvironment: MapProperty<String, String>

    /** The NDK's version, for up-to-date checks; the build uses [ndkDirectory]. */
    @get:Input
    @get:Optional
    abstract val ndkVersion: Property<String>

    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    /** A host build's library, copied from cargo's release directory into [outputDir]. */
    @get:Input
    @get:Optional
    abstract val hostLibrary: Property<String>

    @get:Internal
    abstract val cargo: Property<String>

    @get:Internal
    abstract val workspace: DirectoryProperty

    @get:Internal
    abstract val targetDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val workspaceDir = workspace.get().asFile
        if (!workspaceDir.resolve("Cargo.toml").isFile) {
            throw GradleException(
                "$workspaceDir is empty: the persian-sms-spam submodule is not checked out. " +
                    "Run `git submodule update --init`.",
            )
        }
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        execOps.exec {
            workingDir = workspaceDir
            executable = cargo.get()
            args(arguments.get().map { it.replace("{out}", out.path) })
            environment(cargoEnvironment.get())
            environment("CARGO_TARGET_DIR", targetDir.get().asFile.path)
            ndkDirectory.orNull?.let { environment("ANDROID_NDK_HOME", it.asFile.path) }
        }
        hostLibrary.orNull?.let { name ->
            targetDir.get().file("release/$name").asFile.copyTo(out.resolve(name), overwrite = true)
        }
    }
}

val cargoNdkBuild = tasks.register<CargoBuild>("cargoNdkBuild") {
    description = "Builds the Rust core (libspam_core_jni.so) for each Android ABI."
    sources.from(rustSources)
    workspace.set(rustWorkspace)
    targetDir.set(rustDir.map { it.dir("target") })
    outputDir.set(rustDir.map { it.dir("jniLibs") })
    cargo.set(cargoExecutable)
    cargoEnvironment.set(cargoProfile)
    ndkVersion.set(android.ndkVersion)
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    arguments.set(
        listOf("ndk", "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64") +
            listOf("--platform", "${android.defaultConfig.minSdk}", "-o", "{out}") +
            listOf("build", "--release", "--locked", "-p", "spam-core-jni"),
    )
}

val cargoHostBuild = tasks.register<CargoBuild>("cargoHostBuild") {
    description = "Builds the Rust core for this machine, for the JVM unit tests."
    sources.from(rustSources)
    workspace.set(rustWorkspace)
    targetDir.set(rustDir.map { it.dir("target") })
    outputDir.set(rustDir.map { it.dir("host") })
    cargo.set(cargoExecutable)
    cargoEnvironment.set(cargoProfile)
    hostLibrary.set(System.mapLibraryName("spam_core_jni"))
    arguments.set(listOf("build", "--release", "--locked", "-p", "spam-core-jni"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdkBuild, CargoBuild::outputDir)
    }
}

// The host library for other modules' JVM tests (feature:mldebug).
configurations.consumable("nativeCoreHost") {
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("nospam-native-core-host")) }
    outgoing.artifact(cargoHostBuild.flatMap { it.outputDir })
}

tasks.withType<Test>().configureEach {
    val hostDir = cargoHostBuild.flatMap { it.outputDir }
    inputs.dir(hostDir).withPropertyName("nativeCoreHost").withPathSensitivity(PathSensitivity.RELATIVE)
    jvmArgumentProviders.add { listOf("-Djava.library.path=${hostDir.get().asFile.path}") }
    // Loading a JNI library is a restricted method from JDK 24 (JEP 472): a warning
    // now, refused in a later JDK unless native access is enabled.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
