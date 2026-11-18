// data/runtime-litert — LiteRT (TFLite lineage) adapter behind the InferenceBackend port.
plugins {
    alias(libs.plugins.verispec.android.library)
    alias(libs.plugins.verispec.android.hilt)
}

android {
    namespace = "ai.verispec.data.runtime.litert"
}

dependencies {
    api(project(":data:runtime-api"))
    implementation(libs.litert)
    implementation(libs.okio)
}
