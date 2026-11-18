// app/runner — WorkManager foreground execution engine. One worker per run; the
// worker holds only a ManifestId and re-resolves everything from persistence.
plugins {
    alias(libs.plugins.verispec.android.library)
    alias(libs.plugins.verispec.android.hilt)
}

android {
    namespace = "ai.verispec.app.runner"
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:evidence"))
    implementation(project(":core:clauses"))
    implementation(project(":data:runtime-api"))
    implementation(project(":data:persistence"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.tracing.ktx)
}
