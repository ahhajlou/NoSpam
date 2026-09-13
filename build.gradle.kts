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
}

kover {
    reports {
        total {
            html {
                onCheck = true
                title = "NoSpam merged coverage"
            }
            xml {
                onCheck = true
            }
            verify {
                // Ratchet, not the 80% goal: JVM suites alone cannot cover
                // Compose screens, NotificationCompat builders, or
                // ContentResolver code. Device suites for exactly that code
                // are written (AtomsTest, TelephonyMapperDeviceTest,
                // TelephonyInstrumentedTest, feature UI tests, plus Wave 2A's
                // nine Sqlite*Dao + SqliteNoSpamOpenHelper androidTest suites)
                // and raise this once an emulator runs them. Never lower this
                // bound.
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
                rule("Merged line-coverage ratchet") {
                    minBound(37)
                }
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
    kover(project(":core:data"))
    kover(project(":core:testing"))
    kover(project(":feature:conversations"))
    kover(project(":feature:thread"))
    kover(project(":feature:settings"))
    kover(project(":feature:onboarding"))
    kover(project(":feature:export"))
    kover(project(":feature:mldebug"))
}