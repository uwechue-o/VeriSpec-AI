// tools/analysis — divergence + decay analytics. JVM-only and headless on purpose:
// cross-device divergence cannot be computed on any single device.
plugins {
    alias(libs.plugins.verispec.kotlin.jvm)
    `java-test-fixtures`
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:evidence"))
    implementation(libs.commons.math3)

    // Shared fixtures: realistic bundles with real canonical hashes, reused by
    // the CLI's end-to-end test. Keeps one definition of "a plausible bundle".
    testFixturesApi(project(":core:domain"))
    testFixturesImplementation(project(":core:evidence"))
}
