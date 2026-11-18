// data/runtime-executorch — ExecuTorch (.pte) adapter behind the InferenceBackend port.
plugins {
    alias(libs.plugins.verispec.android.library)
    alias(libs.plugins.verispec.android.hilt)
}

android {
    namespace = "ai.verispec.data.runtime.executorch"
}

dependencies {
    api(project(":data:runtime-api"))
    implementation(libs.executorch.android)
    implementation(libs.okio)
}
