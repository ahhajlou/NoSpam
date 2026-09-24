// SPDX-License-Identifier: GPL-3.0-or-later

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NoSpam"
include(
    ":app",
    ":core:common", ":core:model", ":core:designsystem",
    ":core:database", ":core:telephony", ":core:ml", ":core:notifications",
    ":core:i18n", ":core:preferences", ":core:data", ":core:testing",
    ":feature:conversations", ":feature:thread", ":feature:settings", ":feature:onboarding",
    ":feature:export",
    ":feature:mldebug",
    ":baselineprofile",
)
 