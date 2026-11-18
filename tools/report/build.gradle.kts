// tools/report — JSON + PDF bundle rendering (Apache PDFBox, Apache-2.0; not iText).
plugins {
    alias(libs.plugins.verispec.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:evidence"))
    implementation(project(":tools:analysis"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.pdfbox)
    implementation(libs.xchart)

    // PDFBox logs through SLF4J; bind to no-op so document generation is quiet.
    runtimeOnly(libs.slf4j.nop)
}

/**
 * Renders the two manuals into the top-level DOCS folder, using this module's
 * own PDF tooling rather than an external converter. The original editions
 * (VeriSpec-AI-Manual.pdf, VeriSpec-AI-Developer-Manual.pdf) were retired on
 * 2026-11-16; the _02 editions are the only ones maintained.
 */
tasks.register("generateDocs") {
    group = "documentation"
    description = "Render every document in DOCS/."
    dependsOn("generateManual02", "generateDeveloperManual02", "generateOverview02", "generateReadmeFigures")
}

tasks.register<JavaExec>("generateManual02") {
    group = "documentation"
    description = "Render the plain-language manual to DOCS/VeriSpec-AI-Manual_02.pdf."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ai.verispec.tools.report.docs.GeneralManual02")
    args(rootProject.layout.projectDirectory.file("DOCS/VeriSpec-AI-Manual_02.pdf").asFile.absolutePath)
}

tasks.register<JavaExec>("generateDeveloperManual02") {
    group = "documentation"
    description = "Render the developer manual to DOCS/VeriSpec-AI-Developer-Manual_02.pdf."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ai.verispec.tools.report.docs.DeveloperManual02")
    args(rootProject.layout.projectDirectory.file("DOCS/VeriSpec-AI-Developer-Manual_02.pdf").asFile.absolutePath)
}

tasks.register<JavaExec>("generateOverview02") {
    group = "documentation"
    description = "Render the short plain-language overview to DOCS/VeriSpec-AI-Overview-for-Non-Engineers_02.pdf."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ai.verispec.tools.report.docs.Overview02")
    args(rootProject.layout.projectDirectory.file("DOCS/VeriSpec-AI-Overview-for-Non-Engineers_02.pdf").asFile.absolutePath)
}

tasks.register<JavaExec>("generateReadmeFigures") {
    group = "documentation"
    description = "Render the chart images shown in README.md to DOCS/readme/."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ai.verispec.tools.report.docs.ReadmeFigures")
    args(rootProject.layout.projectDirectory.dir("DOCS/readme").asFile.absolutePath)
}
