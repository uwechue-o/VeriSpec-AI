// data/runtime-llamacpp — llama.cpp JNI adapter (GGUF).
//
// The native build runs when the llama.cpp checkout exists: by default the
// pinned submodule third_party/llama.cpp, or `verispec.llamaCppDir`. Force it
// with `verispec.nativeLlama=true|false`. Without it the module still compiles;
// the adapter then reports the backend as absent, which ExecuteRun records as a
// capability gap.
plugins {
    alias(libs.plugins.verispec.android.library)
    alias(libs.plugins.verispec.android.hilt)
}

val llamaCppDir: String = providers.gradleProperty("verispec.llamaCppDir").orNull?.takeIf { it.isNotBlank() }
    ?: rootProject.file("third_party/llama.cpp").invariantSeparatorsPath
val nativeLlama = when (providers.gradleProperty("verispec.nativeLlama").orNull) {
    "true" -> true
    "false" -> false
    else -> file("$llamaCppDir/CMakeLists.txt").exists()
}

android {
    namespace = "ai.verispec.data.runtime.llamacpp"

    defaultConfig {
        buildConfigField("boolean", "NATIVE_LLAMA_ENABLED", nativeLlama.toString())
        if (nativeLlama) {
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DLLAMA_CPP_DIR=$llamaCppDir",
                        "-DANDROID_STL=c++_static",
                        // Optimised even in the debug variant: an unoptimised ggml
                        // would make every measured tok/s meaningless.
                        "-DCMAKE_BUILD_TYPE=Release",
                        // 16 KB-aligned ELF segments, as Android 15+ devices require.
                        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    )
                    cppFlags += "-std=c++17"
                }
            }
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    if (nativeLlama) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}

dependencies {
    api(project(":data:runtime-api"))
    implementation(libs.okio)
}
