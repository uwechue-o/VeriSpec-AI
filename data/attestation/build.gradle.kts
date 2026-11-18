// data/attestation — Keystore signing, key attestation, device profiling, provenance.
plugins {
    alias(libs.plugins.verispec.android.library)
    alias(libs.plugins.verispec.android.hilt)
}

android {
    namespace = "ai.verispec.data.attestation"
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":data:runtime-api"))
    implementation(project(":core:evidence"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.okio)
}
