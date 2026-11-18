// data/runtime-litert — LiteRT (TFLite lineage) adapter behind the InferenceBackend port.
plugins {
    alias(libs.plugins.verispec.android.library)
    alias(libs.plugins.verispec.android.hilt)
}

android {
    namespace = "ai.verispec.data.runtime.litert"

    defaultConfig {
        externalNativeBuild {
            cmake {
                // 16 KB-aligned ELF segments, as Android 15+ devices require.
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    // A small JNI library that lists the NNAPI devices (src/main/cpp); LiteRT's Java API cannot.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    api(project(":data:runtime-api"))
    implementation(libs.litert)
    implementation(libs.okio)
}
