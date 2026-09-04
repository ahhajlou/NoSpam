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
    alias(libs.plugins.kover)
}

// Kover is applied to the root so merged reports cover every module.
subprojects {
    apply(plugin = "org.jetbrains.kotlinx.kover")
}

kover {
    reports {
        // Dead code under app/src/main/java, kept only until end-of-project
        // cleanup per owner instruction: unregistered from the manifest
        // (receivers/service) or unreferenced (ui/navigation/ml theme).
        // Excluded so the gate measures shippable code.
        filters {
            excludes {
                classes(
                    "com.example.nospam.ui.*",
                    "com.example.nospam.navigation.*",
                    "com.example.nospam.receiver.*",
                    "com.example.nospam.service.*",
                    "com.example.nospam.ml.*",
                    "com.example.nospam.core.telephony.receiver.SmsReceiver",
                )
            }
        }
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
                // ContentResolver code (Robolectric broken on JDK 25, no
                // emulator here). Device suites for exactly that code are
                // written (AtomsTest, TelephonyMapperDeviceTest,
                // TelephonyInstrumentedTest, feature UI tests) and raise this
                // once an emulator runs them. Never lower this bound.
                rule("Merged line-coverage ratchet") {
                    minBound(30)
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
}