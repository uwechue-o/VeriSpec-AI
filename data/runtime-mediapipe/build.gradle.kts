// data/runtime-mediapipe — MediaPipe Tasks GenAI (LLM Inference) adapter.
plugins {
    alias(libs.plugins.verispec.android.library)
    alias(libs.plugins.verispec.android.hilt)
}

android {
    namespace = "ai.verispec.data.runtime.mediapipe"
}

dependencies {
    api(project(":data:runtime-api"))
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.okio)
}
