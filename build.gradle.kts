// SPDX-License-Identifier: GPL-3.0-or-later

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kapt) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.kover)
}

// Kover is applied to the root so merged reports cover every module.
subprojects {
    apply(plugin = "org.jetbrains.kotlinx.kover")

    // "ci": debug-only coverage variant, merged in the root. The default "total"
    // variant also pulls in every Android module's release variant, which made
    // CI compile the whole release graph a second time just to count lines.
    fun ciVariant(source: String) =
        extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
            currentProject { createVariant("ci") { add(source) } }
        }
    plugins.withId("com.android.application") { ciVariant("debug") }
    plugins.withId("com.android.library") { ciVariant("debug") }
    plugins.withId("org.jetbrains.kotlin.jvm") { ciVariant("jvm") }
}

kover {
    currentProject {
        createVariant("ci") {}
    }
    reports {
        total {
            html {
                onCheck = true
                title = "NoSpam merged coverage"
            }
            xml {
                onCheck = true
            }
        }
        variant("ci") {
            html {
                title = "NoSpam merged coverage (debug)"
            }
        }
        // Common to every report variant, so the ratchet gates both the local
        // `:koverVerify` (total) and CI's `:koverVerifyCi` (debug only).
        verify {
            // Ratchet, not the 80% goal: JVM suites alone cannot cover
            // NotificationCompat builders or ContentResolver code. Device
            // suites for exactly that code are written (TelephonyMapperDeviceTest,
            // TelephonyInstrumentedTest, plus Wave 2A's nine Sqlite*Dao +
            // SqliteNoSpamOpenHelper androidTest suites) and raise this once an
            // emulator runs them. Never lower this bound.
            //
            // Robolectric was previously believed broken on this
            // environment's JDK 25 -- it was actually just pinned to
            // 4.11.1, a version that predates JDK 21+ support. Wave 2A
            // bumped it to 4.17, which works fine here, and used it to
            // cover feature:settings' SpamPreferences, core:telephony's
            // PhoneNumberNormalizer/DefaultSmsApp/SmsManagerCompat, and
            // :app's AppContainer/AppSmsReceiver -- see each module's
            // build.gradle.kts for the `forkEvery = 1` note those suites
            // needed once real Robolectric runs surfaced a genuine
            // cross-test static-singleton leak in SpamPreferences.
            //
            // Wave 2A raised merged line coverage from 31.1% (1457/4678)
            // to 38.22% (1788/4678); ratchet moved from 29 to 37, just
            // under the new actual number.
            //
            // 2026-09-17 (UI polish P1.4): Compose screens stopped being
            // uncoverable. Robolectric runs the Compose UI tests on the JVM
            // (see feature:conversations / feature:thread `src/test`), which
            // also sidesteps the API 37 emulator failure in TODO.md. Merged
            // line coverage 49.69% (2711/5456); ratchet 37 -> 49. Settings and
            // the compose bar's counter brought their own suites: 54.88%
            // (3100/5649); ratchet 49 -> 54.
            //
            // 2026-09-20 (P1.9): the duplicated Compose androidTest suites
            // were folded into their Robolectric counterparts and three dead
            // components deleted, so the uncovered Compose surface shrank:
            // 60.39% (3470/5746); ratchet 54 -> 60.
            //
            // 2026-09-23 (phase 2 P2.1-P2.3): preferences, launch intents, the
            // settings pages and the list loading states brought their own
            // suites: 63.56% (3889/6119); ratchet 60 -> 63.
            //
            // 2026-09-24 (phase 2 close-out): 64.20% (4398/6851); ratchet 63 -> 64.
            rule("Merged line-coverage ratchet") {
                minBound(64)
            }
        }
    }
}

// Merging module: classes + coverage from every module roll up into the
// root :koverHtmlReport / :koverXmlReport.
dependencies {
    kover(project(":app"))
    kover(project(":core:common"))
    kover(project(":core:model"))
    kover(project(":core:designsystem"))
    kover(project(":core:database"))
    kover(project(":core:telephony"))
    kover(project(":core:ml"))
    kover(project(":core:notifications"))
    kover(project(":core:i18n"))
    kover(project(":core:preferences"))
    kover(project(":core:data"))
    kover(project(":core:testing"))
    kover(project(":feature:conversations"))
    kover(project(":feature:thread"))
    kover(project(":feature:settings"))
    kover(project(":feature:onboarding"))
    kover(project(":feature:export"))
    kover(project(":feature:mldebug"))
}