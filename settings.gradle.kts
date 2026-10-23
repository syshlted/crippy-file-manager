// encri-files — encri's file manager (AGENTS.md F1). One application module.

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

// Resolves the JDK 21 toolchain on machines where it is not in a location Gradle auto-detects, for
// encri's reason: a newer JDK on PATH silently building against an AGP that does not support it.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "encri-files"

// encri-api, as a build of its own beside this checkout while nothing is published (F1, encri D115).
// Gradle substitutes its projects for their coordinates, so :app names com.systemhalted.encri:encri-client
// as any other app will once it is on Maven Central, and gets the sibling's source until then.
// `encri.api.dir` points it somewhere else, which CI needs, because a clone has no sibling.
includeBuild(providers.gradleProperty("encri.api.dir").getOrElse("../encri-api"))

include(":app")
