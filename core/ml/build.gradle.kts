// SPDX-License-Identifier: GPL-3.0-or-later

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
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
    // The NDK cargo-ndk links the Rust core with (gradle/libs.versions.toml, shared
    // with :app). r28 and later align native libraries for 16 KB pages.
    ndkVersion = libs.versions.ndk.get()

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
        // rustc writes the absolute path of every source file into the library, for
        // panic messages: crates under CARGO_HOME, the workspace, the build directory.
        // Fixed names instead make the library the same on every machine (a
        // reproducible build) and keep local paths, user names included, out of the
        // APK. CARGO_ENCODED_RUSTFLAGS survives spaces in paths; it replaces any
        // RUSTFLAGS from the environment.
        val cargoHome = System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo"
        val remaps = listOf(
            cargoHome to "/cargo",
            workspaceDir.path to "/core",
            targetDir.get().asFile.path to "/target",
        )
        execOps.exec {
            workingDir = workspaceDir
            executable = cargo.get()
            args(arguments.get().map { it.replace("{out}", out.path) })
            environment(cargoEnvironment.get())
            environment("CARGO_TARGET_DIR", targetDir.get().asFile.path)
            environment(
                "CARGO_ENCODED_RUSTFLAGS",
                remaps.joinToString("\u001f") { (from, to) -> "--remap-path-prefix=$from=$to" },
            )
            ndkDirectory.orNull?.let { environment("ANDROID_NDK_HOME", it.asFile.path) }
        }
        hostLibrary.orNull?.let { name ->
            targetDir.get().file("release/$name").asFile.copyTo(out.resolve(name), overwrite = true)
        }
    }
}

// The ABIs here are also :app's abiFilters: keep the two lists the same.
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

/**
 * The crates compiled into libspam_core_jni and each one's license text, for the app's
 * open-source licenses page: `res/raw/rust_licenses.json`, generated. cargo lists the
 * normal, non-proc-macro dependencies of spam-core-jni (the core's own crates, GPL like
 * this app, are left out) and `cargo metadata` gives where it unpacked each crate. Where a
 * crate offers MIT, as every one does today, its own MIT file is shown, copyright line
 * included; a crate shipping no license file gets the standard MIT text with its authors.
 */
@DisableCachingByDefault(because = "runs cargo; cheap, and its inputs are the lock file")
abstract class RustLicenses @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
    /** The manifests and the lock file: what decides the crates and their versions. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifests: ConfigurableFileCollection

    @get:Internal
    abstract val cargo: Property<String>

    @get:Internal
    abstract val workspace: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private fun cargo(vararg args: String): String {
        val out = ByteArrayOutputStream()
        execOps.exec {
            workingDir = workspace.get().asFile
            executable = cargo.get()
            args(*args)
            standardOutput = out
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun licenseText(dir: File, license: String, authors: List<String>): String {
        val files = dir.listFiles().orEmpty()
            .filter { it.isFile && Regex("(?i)^(licen[cs]e|copying)").containsMatchIn(it.name) }
            .sortedBy { it.name }
        val mit = if ("MIT" in license) {
            files.firstOrNull { "MIT" in it.name.uppercase() } ?: files.singleOrNull()
        } else {
            null
        }
        return when {
            mit != null -> mit.readText()
            files.isNotEmpty() -> files.joinToString("\n\n") { it.readText() }
            else -> "Copyright (c) ${authors.joinToString(", ") { it.substringBefore(" <") }}\n\n$MIT_TEXT"
        }.trim()
    }

    @TaskAction
    fun generate() {
        // "name vX.Y.Z", "name vX.Y.Z (*)" for a repeat, "name vX.Y.Z (/path)" for the core's own crates.
        val crates = cargo(
            "tree", "-p", "spam-core-jni", "-e", "normal,no-proc-macro", "--target", "aarch64-linux-android",
            "--locked", "--prefix", "none", "--format", "{p}",
        ).lines().map { it.removeSuffix(" (*)").trim() }
            .filter { it.isNotEmpty() && " (" !in it }
            .map { it.substringBefore(" v") to it.substringAfter(" v") }
            .toSet()
        @Suppress("UNCHECKED_CAST")
        val packages = (JsonSlurper().parseText(cargo("metadata", "--format-version", "1", "--locked")) as Map<String, Any?>)
            .getValue("packages") as List<Map<String, Any?>>
        val entries = packages
            .filter { (it["name"] as String to it["version"] as String) in crates }
            .sortedWith(compareBy({ it["name"] as String }, { it["version"] as String }))
            .map { p ->
                @Suppress("UNCHECKED_CAST")
                val authors = (p["authors"] as List<String>?).orEmpty()
                val license = p["license"] as String? ?: "see repository"
                mapOf(
                    "name" to p["name"],
                    "version" to p["version"],
                    "license" to license,
                    "repository" to p["repository"],
                    "text" to licenseText(File(p["manifest_path"] as String).parentFile, license, authors),
                )
            }
        check(entries.size == crates.size) { "license data for ${entries.size} of ${crates.size} crates" }
        val raw = outputDir.get().dir("raw").asFile
        raw.deleteRecursively()
        raw.mkdirs()
        raw.resolve("rust_licenses.json").writeText(JsonOutput.toJson(mapOf("crates" to entries)))
    }

    private companion object {
        const val MIT_TEXT = """Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""
    }
}

val rustLicenses = tasks.register<RustLicenses>("rustLicenses") {
    description = "Lists the crates in the Rust core with their license texts, for the app's licenses page."
    manifests.from(rustWorkspace.asFileTree.matching { include("Cargo.toml", "Cargo.lock", "*/Cargo.toml") })
    cargo.set(cargoExecutable)
    workspace.set(rustWorkspace)
    outputDir.set(layout.buildDirectory.dir("generated/rustLicenses"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdkBuild, CargoBuild::outputDir)
        variant.sources.res?.addGeneratedSourceDirectory(rustLicenses, RustLicenses::outputDir)
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
