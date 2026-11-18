// tools/cli — fleet orchestrator over ADB, bundle verifier and report renderer.
plugins {
    alias(libs.plugins.verispec.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("ai.verispec.tools.cli.MainKt")
    applicationName = "verispec"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:evidence"))
    implementation(project(":core:clauses"))
    implementation(project(":tools:analysis"))
    implementation(project(":tools:report"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.clikt)
    implementation(libs.mordant)
    implementation(libs.adam)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.okio)

    // PDFBox logs through SLF4J; without a binding it prints a warning banner
    // on every report. The CLI has its own output, so bind to no-op.
    runtimeOnly(libs.slf4j.nop)

    // Test-only: the analysis fixtures and a self-signed X.509 leaf so signature
    // verification in the end-to-end test runs against a real certificate.
    testImplementation(testFixtures(project(":tools:analysis")))
    testImplementation(project(":core:clauses"))
}

/**
 * Writes a pair of realistic sample bundles into build/samples so the CLI can be
 * driven by hand without a device. Not part of `build`.
 */
tasks.register<JavaExec>("writeSampleBundles") {
    group = "verification"
    description = "Generate sample evidence bundles for manual CLI exercise."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ai.verispec.tools.cli.SampleBundleWriter")
    args(layout.buildDirectory.dir("samples").get().asFile.absolutePath)
}
