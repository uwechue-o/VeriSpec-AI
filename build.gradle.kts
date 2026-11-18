// Root build — declares plugin versions once; modules apply convention plugins.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    alias(libs.plugins.bcv)

    // Enforces the inward-only dependency rule across every subproject.
    alias(libs.plugins.verispec.dependency.rules)
}

// ---- Static analysis ---------------------------------------------------------
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(
        subprojects.flatMap { p ->
            listOf("src/main/kotlin", "src/main/java", "src/test/kotlin").map { p.file(it) }
        },
    )
}

// ---- Coverage: aggregate every module into the root report ------------------
dependencies {
    subprojects.forEach { sub ->
        sub.plugins.withId("org.jetbrains.kotlinx.kover") {
            kover(sub)
        }
    }
}

// ---- Public API surface is part of the evidence contract --------------------
apiValidation {
    // Only the pure core modules publish a checked API. Everything else is an
    // implementation detail and may change freely.
    ignoredProjects.addAll(
        listOf(
            "runtime-api", "runtime-litert", "runtime-llamacpp", "runtime-executorch",
            "runtime-mediapipe", "telemetry", "attestation", "persistence",
            "ui", "runner", "cli", "analysis", "report",
        ),
    )
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
