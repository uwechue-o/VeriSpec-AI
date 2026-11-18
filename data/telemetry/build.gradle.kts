// data/telemetry — thermal, power, memory and CPU-frequency sources (platform APIs).
plugins {
    alias(libs.plugins.verispec.android.library)
    alias(libs.plugins.verispec.android.hilt)
}

android {
    namespace = "ai.verispec.data.telemetry"
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":data:runtime-api"))
    implementation(libs.androidx.core.ktx)
}
