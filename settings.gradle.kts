// VeriSpec-AI — multi-module Gradle settings.
// Module topology mirrors docs in GUIDE.md §2. Dependency direction is enforced
// mechanically by the `verispec.dependency.rules` convention plugin.

pluginManagement {
    includeBuild("build-logic")
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
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

rootProject.name = "Verispec-AI"

// ---- core: pure Kotlin/JVM, zero Android ----------------------------------
include(":core:domain")
include(":core:evidence")
include(":core:clauses")

// ---- data: ports & adapters -----------------------------------------------
include(":data:runtime-api")
include(":data:runtime-litert")
include(":data:runtime-llamacpp")
include(":data:runtime-executorch")
include(":data:runtime-mediapipe")
include(":data:telemetry")
include(":data:attestation")
include(":data:persistence")

// ---- app: operator console + execution engine -----------------------------
include(":app:ui")
include(":app:runner")

// ---- tools: JVM-only, headless -------------------------------------------
include(":tools:cli")
include(":tools:analysis")
include(":tools:report")
